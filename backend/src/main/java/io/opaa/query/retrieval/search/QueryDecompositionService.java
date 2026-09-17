package io.opaa.query.retrieval.search;

import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.observability.QueryMetrics;
import io.opaa.query.answer.ChatResponses;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

/**
 * Splits a question into up to {@code maxSubQueries} independent, self-contained search queries
 * (docs/handbuch/suche.md, Stufe 3), resolving a conversation-relative follow-up into a standalone
 * question in the process.
 *
 * <p>{@link #decompose} never throws: any LLM failure, unparsable or empty response, or output
 * unrelated to the question (see {@link #countUnrelated}) yields {@link Optional#empty()}, which
 * {@link SubQueryDecompositionStage} takes as "run the single-query fallback". Every such fallback
 * is logged at WARN with counts only - never with the question or the sub-queries - and counted on
 * {@code opaa.query.decomposition.fallback}, so no path out of here is silent. A message without
 * anything to search for is <b>not</b> a fallback: the model answers {@link #NO_SEARCH_SENTINEL},
 * which yields an empty list and is counted on {@code opaa.query.decomposition.no-search}. The
 * {@link ChatClient} is resolved fresh per call, from the same systemwide active chat model the
 * answer uses.
 */
@Service
public class QueryDecompositionService {

  private static final Logger log = LoggerFactory.getLogger(QueryDecompositionService.class);

  /**
   * A leading bullet/numbering marker a model might add to each line despite the prompt's
   * instruction not to ("- ", "* ", "1. ", "2) ") - stripped so the rest of the line is used as the
   * search query verbatim, mirroring {@code ChatTitleGenerationService#sanitize}'s equally
   * defensive handling of a model that does not follow formatting instructions exactly.
   */
  private static final Pattern LEADING_BULLET_OR_NUMBER =
      Pattern.compile("^[-*•]\\s+|^\\d+[.)]\\s+");

  /** Word separator for the anchor tokens of {@link #countUnrelated}. */
  private static final Pattern NON_WORD_CHARACTERS = Pattern.compile("[^\\p{L}\\p{N}]+");

  /**
   * Minimum length of a token that counts as an anchor: shorter German words are almost entirely
   * function words ("und", "was", "das", "der"), which two unrelated sentences share by chance.
   */
  private static final int ANCHOR_MIN_LENGTH = 4;

  /** What the model answers for a message without anything to search for. */
  static final String NO_SEARCH_SENTINEL = "KEINE_SUCHE";

  /** Compares a line to the sentinel regardless of case, spacing and punctuation. */
  private static final Pattern NON_LETTERS = Pattern.compile("[^\\p{L}]+");

  /**
   * The labels of {@link DecompositionContext#promptMessages()}, as a model copies them in front.
   */
  private static final Pattern LEADING_LABELS =
      Pattern.compile(
          "^(?:(?:"
              + Stream.of(
                      DecompositionContext.WINDOW_LABEL,
                      DecompositionContext.QUESTION_LABEL,
                      DecompositionContext.USER_LABEL,
                      DecompositionContext.ASSISTANT_LABEL)
                  .map(label -> Pattern.quote(label.replace(":", "").strip()))
                  .collect(Collectors.joining("|"))
              + ")\\s*:\\s*)+",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  /**
   * A line made of these words alone is a label, not a search query: the words of the labels plus
   * the few a model wraps them in ("Antwort auf die aktuelle Nutzerfrage:").
   */
  private static final Set<String> LABEL_LINE_WORDS =
      Stream.concat(
              Stream.of(
                      DecompositionContext.WINDOW_LABEL,
                      DecompositionContext.QUESTION_LABEL,
                      DecompositionContext.USER_LABEL,
                      DecompositionContext.ASSISTANT_LABEL)
                  .flatMap(label -> Stream.of(NON_LETTERS.split(label))),
              Stream.of("antwort", "auf", "die", "zur", "frage", "suchanfrage", "suchanfragen"))
          .filter(word -> !word.isEmpty())
          .map(word -> word.toLowerCase(Locale.ROOT))
          .collect(Collectors.toUnmodifiableSet());

  /**
   * Deliberately carries <b>no</b> example sentence: a small instruct model regularly mistakes an
   * example inside a rule for the task itself and returns it verbatim, discarding the user's
   * question while still looking like a successful decomposition. The output format is therefore
   * described, never demonstrated; the sentinel rule names sentence beginnings, not sentences.
   *
   * <p>Equally deliberately a <b>fixed</b> text, the Gesprächsnotiz rule included: the rule is an
   * instruction, not context, and stating it unconditionally keeps this template free of anything
   * derived from the run - which is what {@link DecompositionContext}'s anchor-space invariant
   * rests on. The note itself arrives as a context block, never here.
   */
  private static final String SYSTEM_PROMPT_TEMPLATE =
      """
      Du zerlegst die aktuelle Nutzerfrage unter Berücksichtigung des bisherigen Gesprächsverlaufs \
      in 1 bis %d eigenständige, vollständige Suchanfragen für eine Vektorsuche in einer \
      Wissensdatenbank. Du bist nicht Teil des Gesprächs: Der Verlauf ist nur Material, um Bezüge \
      aufzulösen.

      Regeln:
      - Verwende ausschließlich den Inhalt der aktuellen Nutzerfrage und des bisherigen \
      Gesprächsverlaufs. Führe kein neues Thema ein.
      - Enthält die Frage mehrere eigenständige Themen, erzeuge für jedes Thema eine eigene, \
      vollständige Suchanfrage.
      - Bezieht sich die Frage auf den bisherigen Gesprächsverlauf, ersetze jedes rückverweisende \
      Wort durch den Gegenstand aus dem Verlauf, den es meint. Das Ergebnis bleibt eine Frage und \
      enthält denselben Gegenstand wie der Verlauf.
      - Enthält der Kontext eine Gesprächsnotiz, verwende sie ausschließlich, um rückverweisende \
      oder unterbestimmte Wörter aufzulösen. Eine bereits eigenständige Frage bleibt unverändert.
      - Korrigiere offensichtliche Tippfehler in der Frage.
      - Ist die Frage bereits eigenständig und einthemig, gib genau eine Suchanfrage zurück - bei \
      Bedarf wortgleich zur Eingabe.
      - Besteht die aktuelle Nachricht nur aus einem Wunsch zur Form der Antwort, einem Dank, einer \
      Zustimmung oder einer Angabe zur eigenen Person, die am Gegenstand des Verlaufs nichts ändert, \
      gib genau das Wort %s zurück und nichts sonst. Führt die Nachricht einen Gegenstand des \
      Verlaufs fort - auch als unvollständiger Satz, als Bedingung, die mit wenn ich oder falls \
      beginnt, als Anschluss, der mit und für beginnt, als Angabe zu Alter, Lage oder Umständen, die \
      für diesen Gegenstand zählen, oder ohne Fragezeichen -, ist sie eine Frage: Gib den Gegenstand \
      des Verlaufs zusammen mit dieser Angabe als Suchanfrage zurück.
      - Beantworte die Frage nicht, bewerte sie nicht und setze das Gespräch nicht fort. Gib nur \
      Suchanfragen zurück, je Zeile genau eine, ohne Nummerierung, ohne Aufzählungszeichen, ohne \
      Anführungszeichen, ohne Einleitung und ohne Erklärung.
      """;

  private final ActiveChatModelResolver activeChatModelResolver;
  private final QueryMetrics metrics;

  QueryDecompositionService(ActiveChatModelResolver activeChatModelResolver, QueryMetrics metrics) {
    this.activeChatModelResolver = activeChatModelResolver;
    this.metrics = metrics;
  }

  /**
   * The search queries {@code context} needs: 1 to {@code maxSubQueries} self-contained ones, an
   * empty list for a message without anything to search for, or {@link Optional#empty()} on any
   * failure - see this class's Javadoc. {@code context} carries both the material the model is
   * given and, by the anchor-space invariant of {@link DecompositionContext}, the material {@link
   * #countUnrelated} judges the output against.
   */
  public Optional<List<String>> decompose(DecompositionContext context, int maxSubQueries) {
    try {
      String rawResponse = requestDecomposition(context, maxSubQueries);
      List<String> lines = parse(rawResponse);
      List<String> parsed = lines.stream().filter(line -> !isNoSearchSentinel(line)).toList();
      if (parsed.isEmpty() && !lines.isEmpty()) {
        metrics.recordNoSearchDecomposition();
        log.debug("Query decomposition found nothing to search for - single-query fallback");
        return Optional.of(List.of());
      }
      if (parsed.isEmpty()) {
        metrics.recordFailedDecomposition();
        log.warn(
            "Query decomposition returned no usable line - falling back to single-query"
                + " retrieval");
        return Optional.empty();
      }
      long unrelated = countUnrelated(parsed, context);
      if (unrelated > 0) {
        boolean allUnrelated = unrelated == parsed.size();
        if (allUnrelated) {
          metrics.recordDegenerateDecomposition();
        } else {
          metrics.recordPrunedDecomposition();
        }
        // Counts only, never the sub-queries themselves: they are reformulations of what the user
        // wrote, which docs/features/security-and-compliance.md keeps out of the application log.
        log.warn(
            "Query decomposition discarded ({}): {} of {} sub-queries have no word relation to the"
                + " question - falling back to single-query retrieval",
            allUnrelated ? "degenerate" : "pruned",
            unrelated,
            parsed.size());
        log.debug("Discarded sub-query lengths: {}", parsed.stream().map(String::length).toList());
        return Optional.empty();
      }
      return Optional.of(
          parsed.size() <= maxSubQueries ? parsed : parsed.subList(0, maxSubQueries));
    } catch (RuntimeException e) {
      metrics.recordFailedDecomposition();
      log.warn("Query decomposition failed - falling back to single-query retrieval", e);
      return Optional.empty();
    }
  }

  /**
   * Whether {@code line} is {@link #NO_SEARCH_SENTINEL}. Next to a search query the sentinel is
   * ignored rather than honoured: a search that was not needed costs less than one that was
   * skipped.
   */
  private static boolean isNoSearchSentinel(String line) {
    return NON_LETTERS
        .matcher(line)
        .replaceAll("")
        .equalsIgnoreCase(NON_LETTERS.matcher(NO_SEARCH_SENTINEL).replaceAll(""));
  }

  private static boolean isLabelLine(String line) {
    return Stream.of(NON_LETTERS.split(line))
        .filter(word -> !word.isEmpty())
        .allMatch(word -> LABEL_LINE_WORDS.contains(word.toLowerCase(Locale.ROOT)));
  }

  private String requestDecomposition(DecompositionContext context, int maxSubQueries) {
    String systemText =
        context.systemText(SYSTEM_PROMPT_TEMPLATE.formatted(maxSubQueries, NO_SEARCH_SENTINEL));
    List<Message> messages = context.promptMessages();

    ChatClient chatClient = activeChatModelResolver.resolveChatClient();
    ChatResponse response =
        chatClient.prompt().system(systemText).messages(messages).call().chatResponse();
    // A model call that returns nothing at all is a decomposition failure like any other here:
    // the caller falls back to single-query retrieval instead of failing the turn.
    return response == null ? null : ChatResponses.textOrNull(response);
  }

  /**
   * Splits {@code rawText} into non-blank, deduplicated lines without a leading bullet or prompt
   * label, dropping a line of label words alone. Not capped here: {@link #decompose} judges
   * relatedness over the model's whole output before truncating, so a degenerate trailing line
   * cannot displace a usable one out of the judged window. Returns an empty list for {@code null},
   * blank, or otherwise unusable input.
   */
  private List<String> parse(String rawText) {
    if (rawText == null || rawText.isBlank()) {
      return List.of();
    }
    return rawText
        .strip()
        .lines()
        .map(String::strip)
        .map(line -> LEADING_BULLET_OR_NUMBER.matcher(line).replaceFirst(""))
        .map(line -> LEADING_LABELS.matcher(line.strip()).replaceFirst(""))
        .map(String::strip)
        .filter(line -> !line.isEmpty() && !isLabelLine(line))
        .distinct()
        .toList();
  }

  /**
   * How many of {@code subQueries} share no anchor token with {@code context}: a decomposition is a
   * reformulation of what the user asked in the context it was asked in, so a sub-query without a
   * single word in common with any of that material is model output that replaced the question.
   *
   * <p>The anchor space is {@link DecompositionContext#contextTexts()} - the material the model was
   * given, minus the boilerplate of any rendered block, by construction rather than by an
   * enumeration here. See that method for why a block's own heading must stay out of it.
   *
   * <p>All or nothing: {@link #decompose} falls back as soon as this returns anything above zero,
   * because dropping one sub-query of a correct decomposition loses a whole topic. The check is
   * skipped - returning zero - when the context yields at most one anchor, and for a script without
   * word separators, where every sub-query would look unrelated.
   */
  private static long countUnrelated(List<String> subQueries, DecompositionContext context) {
    Set<String> anchors = new HashSet<>();
    context.contextTexts().forEach(text -> anchors.addAll(tokenize(text)));
    if (anchors.size() <= 1 || lacksWordBoundaries(context.question())) {
      return 0;
    }
    return subQueries.stream().filter(subQuery -> !isRelated(subQuery, anchors)).count();
  }

  private static boolean lacksWordBoundaries(String question) {
    Set<String> tokens = tokenize(question);
    if (tokens.size() != 1) {
      return false;
    }
    long wordCharacters = question.codePoints().filter(Character::isLetterOrDigit).count();
    return tokens.iterator().next().length() == wordCharacters;
  }

  /**
   * True as soon as one token of {@code subQuery} contains an anchor or is contained in one. That
   * covers prefix-stable inflection and compounding ("Gebühr" in "Gebührenbefreiung") but not forms
   * whose shared stem is broken by umlaut or composition ("Buch"/"Bücher", "Mahnung"/"Mahngebühr")
   * - the check separates a reformulation from a wholly different sentence, it is not a stemmer.
   */
  private static boolean isRelated(String subQuery, Set<String> anchors) {
    return tokenize(subQuery).stream()
        .anyMatch(
            token ->
                anchors.stream()
                    .anyMatch(anchor -> token.contains(anchor) || anchor.contains(token)));
  }

  private static Set<String> tokenize(String text) {
    if (text == null || text.isBlank()) {
      return Set.of();
    }
    return NON_WORD_CHARACTERS
        .splitAsStream(text.toLowerCase(Locale.ROOT))
        .filter(token -> token.length() >= ANCHOR_MIN_LENGTH)
        .collect(Collectors.toSet());
  }
}
