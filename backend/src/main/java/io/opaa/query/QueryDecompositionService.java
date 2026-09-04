package io.opaa.query;

import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.observability.QueryMetrics;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

/**
 * Splits a question into up to {@code maxSubQueries} independent, self-contained search queries
 * before retrieval (#923), resolving a conversation-relative follow-up into a standalone question
 * in the process - replacing {@code QueryService}'s previous "always prepend the first chat
 * message" heuristic on every path that reaches this class successfully. {@link #decompose} never
 * throws: any LLM failure, unparsable/empty response or output unrelated to the question (see
 * {@link #countUnrelated}) yields an empty list, which {@code QueryService} treats as "run the
 * single-query fallback unchanged", never a user-facing error. Every such fallback is logged at
 * WARN with counts only - never with the question or the sub-queries - and counted on {@code
 * opaa.query.decomposition.fallback}, so no path out of here is silent. Resolves the {@link
 * ChatClient} fresh via {@link ActiveChatModelResolver#resolveChatClient()} on every call - the
 * same systemwide active chat model {@code AnswerGenerationService} uses for the answer itself.
 */
@Service
class QueryDecompositionService {

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

  /**
   * Deliberately carries <b>no</b> example sentence (#1254). A small instruct model regularly
   * mistakes an example inside a rule for the task itself and returns the example verbatim, which
   * discards the user's question entirely while still looking like a successful decomposition. The
   * output format is therefore described, never demonstrated; the user's question is the only
   * content the model sees. The follow-up resolution of #923 stays in as a rule - it is what makes
   * a conversation-relative question searchable - only its illustration is gone.
   */
  private static final String SYSTEM_PROMPT_TEMPLATE =
      """
      Du zerlegst die aktuelle Nutzerfrage unter Berücksichtigung des bisherigen Gesprächsverlaufs \
      in 1 bis %d eigenständige, vollständige Suchanfragen für eine Vektorsuche in einer \
      Wissensdatenbank.

      Regeln:
      - Verwende ausschließlich den Inhalt der aktuellen Nutzerfrage und des bisherigen \
      Gesprächsverlaufs. Führe kein neues Thema ein.
      - Enthält die Frage mehrere eigenständige Themen, erzeuge für jedes Thema eine eigene, \
      vollständige Suchanfrage.
      - Bezieht sich die Frage auf den bisherigen Gesprächsverlauf, ersetze jedes rückverweisende \
      Wort durch den Gegenstand aus dem Verlauf, den es meint. Das Ergebnis bleibt eine Frage und \
      enthält denselben Gegenstand wie der Verlauf.
      - Korrigiere offensichtliche Tippfehler in der Frage.
      - Ist die Frage bereits eigenständig und einthemig, gib genau eine Suchanfrage zurück - bei \
      Bedarf wortgleich zur Eingabe.
      - Beantworte die Frage nicht und bewerte sie nicht. Gib nur Suchanfragen zurück, je Zeile \
      genau eine, ohne Nummerierung, ohne Aufzählungszeichen, ohne Anführungszeichen, ohne \
      Einleitung und ohne Erklärung.
      """;

  private final ActiveChatModelResolver activeChatModelResolver;
  private final QueryMetrics metrics;

  QueryDecompositionService(ActiveChatModelResolver activeChatModelResolver, QueryMetrics metrics) {
    this.activeChatModelResolver = activeChatModelResolver;
    this.metrics = metrics;
  }

  /**
   * Returns 1 to {@code maxSubQueries} self-contained search queries derived from {@code question}
   * and {@code conversationHistory}, or an empty list on any failure - see this class's Javadoc.
   * {@code conversationHistory} is passed through unchanged (already bounded by {@code
   * QueryConfiguration#MAX_MESSAGES_PER_CONVERSATION}), the same history {@code
   * AnswerGenerationService#generateAnswer} sees for the same query.
   */
  List<String> decompose(String question, List<Message> conversationHistory, int maxSubQueries) {
    try {
      String rawResponse = requestDecomposition(question, conversationHistory, maxSubQueries);
      List<String> parsed = parse(rawResponse);
      if (parsed.isEmpty()) {
        metrics.recordFailedDecomposition();
        log.warn(
            "Query decomposition returned no usable line - falling back to single-query"
                + " retrieval");
        return List.of();
      }
      long unrelated = countUnrelated(parsed, question, conversationHistory);
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
        return List.of();
      }
      return parsed.size() <= maxSubQueries ? parsed : parsed.subList(0, maxSubQueries);
    } catch (RuntimeException e) {
      metrics.recordFailedDecomposition();
      log.warn("Query decomposition failed - falling back to single-query retrieval", e);
      return List.of();
    }
  }

  private String requestDecomposition(
      String question, List<Message> conversationHistory, int maxSubQueries) {
    String systemText = SYSTEM_PROMPT_TEMPLATE.formatted(maxSubQueries);
    List<Message> messages = new ArrayList<>(conversationHistory);
    messages.add(new UserMessage(question));

    ChatClient chatClient = activeChatModelResolver.resolveChatClient();
    ChatResponse response =
        chatClient.prompt().system(systemText).messages(messages).call().chatResponse();
    if (response == null
        || response.getResult() == null
        || response.getResult().getOutput() == null) {
      return null;
    }
    return response.getResult().getOutput().getText();
  }

  /**
   * Splits {@code rawText} into non-blank, deduplicated, bullet-stripped lines. Not capped here:
   * {@link #decompose} judges relatedness over the model's whole output before truncating, so a
   * degenerate trailing line cannot displace a usable one out of the judged window. Returns an
   * empty list for {@code null}, blank, or otherwise unusable input.
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
        .map(String::strip)
        .filter(line -> !line.isEmpty())
        .distinct()
        .toList();
  }

  /**
   * How many of {@code subQueries} share no anchor token with the question or the conversation
   * history (#1254): a decomposition is a reformulation of what the user asked, so a sub-query
   * without a single word in common with either is not one - it is model output that replaced the
   * question instead of restating it.
   *
   * <p><b>All or nothing.</b> {@link #decompose} falls back as soon as this returns anything above
   * zero, rather than searching with the remainder: dropping one sub-query of a correct
   * decomposition loses a whole topic of the question, which is strictly worse than the
   * undecomposed question that {@code SubQueryDecompositionStage#buildSearchQuery} falls back to.
   *
   * <p>Returns zero - the check is skipped - in two cases only. First, when question and history
   * together yield at most one anchor: there is nothing to relate against. The history counts here,
   * so a short follow-up ("Und was kostet das?") is judged against the conversation it resolves
   * into, not left unchecked. Second, for a script without word separators (Chinese, Japanese,
   * Thai), recognised by the question collapsing into a single token that spans all of its word
   * characters - there, every sub-query would look unrelated.
   */
  private static long countUnrelated(
      List<String> subQueries, String question, List<Message> conversationHistory) {
    Set<String> anchors = new HashSet<>(tokenize(question));
    conversationHistory.forEach(message -> anchors.addAll(tokenize(message.getText())));
    if (anchors.size() <= 1 || lacksWordBoundaries(question)) {
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
