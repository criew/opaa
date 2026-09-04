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
 * throws: any LLM failure, unparsable/empty response or degenerate output (see {@link
 * #retainRelated}) yields an empty list, which {@code QueryService} treats as "run the single-query
 * fallback unchanged", never a user-facing error. Every such fallback is logged at WARN and counted
 * ({@link QueryMetrics#recordDegenerateDecomposition()}, {@link
 * QueryMetrics#recordFailedDecomposition()}) - it is never silent. Resolves the {@link ChatClient}
 * fresh via {@link ActiveChatModelResolver#resolveChatClient()} on every call - the same systemwide
 * active chat model {@code AnswerGenerationService} uses for the answer itself.
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

  /** Word separator for the anchor tokens of {@link #retainRelated}. */
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
   * content the model sees.
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
      - Bezieht sich die Frage erkennbar auf den bisherigen Gesprächsverlauf, löse den Bezug auf \
      und formuliere eine vollständige, in sich verständliche Frage ohne diesen Bezug.
      - Korrigiere offensichtliche Tippfehler in der Frage.
      - Ist die Frage bereits eigenständig und einthemig, gib genau eine Suchanfrage zurück - bei \
      Bedarf wortgleich zur Eingabe.

      Ausgabeformat: ausschließlich die Suchanfragen, je Zeile genau eine, ohne Nummerierung, ohne \
      Aufzählungszeichen, ohne Anführungszeichen, ohne Einleitung und ohne Erklärung.
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
      List<String> parsed = parse(rawResponse, maxSubQueries);
      List<String> subQueries = retainRelated(parsed, anchorTokens(question, conversationHistory));
      if (subQueries.isEmpty()) {
        metrics.recordDegenerateDecomposition();
        log.warn(
            "Query decomposition produced no sub-query related to the question - falling back to"
                + " single-query retrieval. Discarded output: {}",
            parsed);
      } else if (subQueries.size() < parsed.size()) {
        log.warn(
            "Query decomposition produced sub-queries unrelated to the question; keeping {} of {}."
                + " Full output: {}",
            subQueries.size(),
            parsed.size(),
            parsed);
      }
      return subQueries;
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
   * Splits {@code rawText} into non-blank, deduplicated, bullet-stripped lines, capped at {@code
   * maxSubQueries} - never grows the number of sub-queries beyond that bound even if the model
   * ignores the prompt's instruction and emits more. Returns an empty list for {@code null}, blank,
   * or otherwise unusable input, which {@link #decompose} treats as a decomposition failure.
   */
  private List<String> parse(String rawText, int maxSubQueries) {
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
        .limit(maxSubQueries)
        .toList();
  }

  /**
   * Drops every sub-query that shares no anchor token with the question or the conversation history
   * (#1254): a decomposition is a reformulation of what the user asked, so a sub-query without a
   * single word in common with either is not one - it is model output that replaced the question
   * instead of restating it, and searching for it discards the user's request silently. An empty
   * result is a degenerate decomposition and makes {@link #decompose} fall back.
   *
   * <p>Skipped when the input carries no anchor token of its own (a question made up entirely of
   * short function words): there would be nothing to relate a sub-query to, and every output would
   * be rejected.
   */
  private static List<String> retainRelated(List<String> subQueries, Set<String> anchors) {
    if (anchors.isEmpty()) {
      return subQueries;
    }
    return subQueries.stream().filter(subQuery -> isRelated(subQuery, anchors)).toList();
  }

  /**
   * True as soon as one token of {@code subQuery} contains an anchor or is contained in one -
   * inflection and compounding ("Gebühr" in "Gebührenbefreiung") must not count as unrelated, and
   * this filter only has to separate a reformulation from a wholly different sentence.
   */
  private static boolean isRelated(String subQuery, Set<String> anchors) {
    return tokenize(subQuery).stream()
        .anyMatch(
            token ->
                anchors.stream()
                    .anyMatch(anchor -> token.contains(anchor) || anchor.contains(token)));
  }

  private static Set<String> anchorTokens(String question, List<Message> conversationHistory) {
    Set<String> anchors = new HashSet<>(tokenize(question));
    conversationHistory.forEach(message -> anchors.addAll(tokenize(message.getText())));
    return anchors;
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
