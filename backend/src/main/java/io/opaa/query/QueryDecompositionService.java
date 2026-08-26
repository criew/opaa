package io.opaa.query;

import io.opaa.llm.ActiveChatModelResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

/**
 * Splits a question into up to {@code maxSubQueries} independent, self-contained search queries
 * before retrieval (#923, Maßnahmen B+C aus #912): a multi-topic question ("Was kostet ein neuer
 * Personalausweis und ein Führerschein?") becomes one search query per topic instead of a single
 * search vector that structurally favors whichever topic scores highest, and a context-dependent
 * follow-up ("und was kostet das?") is resolved against the conversation history into a
 * self-contained question - replacing {@code QueryService}'s previous "always prepend the first
 * chat message" heuristic on every path that reaches this class successfully (see {@code
 * QueryService#query}'s Javadoc for the fallback that heuristic still serves).
 *
 * <p><b>Never throws and never blocks a query on a broken decomposition.</b> {@link #decompose}
 * returns an empty list - not an exception - for an LLM failure (timeout, no active model) or an
 * unparsable/empty response; {@code QueryService#query} treats an empty list as "run the fallback,
 * single-query path unchanged" (#923's explicit robustness requirement: "unparsebare Ausgabe =
 * Fallback, kein Fehler").
 *
 * <p><b>Uses the same systemwide active chat model as answer generation</b> ({@link
 * ActiveChatModelResolver#resolveChatClient()}, resolved fresh on every call, exactly like {@code
 * AnswerGenerationService}/{@code ChatTitleGenerationService} - see either's Javadoc for why a
 * cached {@link ChatClient} field would miss a model activation via the admin API). This is
 * deliberately the same model as answer generation, not a separately configured "small/fast" one:
 * the project has exactly one managed-model connection path
 * (docs/features/llm-integration.md#ein-anbindungsweg-nicht-zwei), and introducing a second
 * configurable model slot for this one call is not justified before an operator actually needs it.
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

  private static final String SYSTEM_PROMPT_TEMPLATE =
      """
      Du zerlegst die aktuelle Nutzerfrage unter Berücksichtigung des bisherigen Gesprächsverlaufs \
      in 1 bis %d eigenständige, vollständige Suchanfragen für eine Vektorsuche in einer \
      Wissensdatenbank.

      Regeln:
      - Enthält die Frage mehrere eigenständige Themen, erzeuge für jedes Thema eine eigene, \
      vollständige Suchanfrage.
      - Bezieht sich die Frage erkennbar auf den bisherigen Gesprächsverlauf (z. B. eine \
      Folgefrage wie "und was kostet das?"), löse den Bezug auf und formuliere eine \
      vollständige, in sich verständliche Frage ohne diesen Bezug.
      - Korrigiere offensichtliche Tippfehler in der Frage.
      - Ist die Frage bereits eigenständig und einthemig, gib genau eine Suchanfrage zurück - bei \
      Bedarf wortgleich zur Eingabe.
      - Antworte ausschließlich mit den Suchanfragen, eine pro Zeile, ohne Nummerierung, ohne \
      Aufzählungszeichen, ohne Erklärung.
      """;

  private final ActiveChatModelResolver activeChatModelResolver;

  QueryDecompositionService(ActiveChatModelResolver activeChatModelResolver) {
    this.activeChatModelResolver = activeChatModelResolver;
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
      List<String> subQueries = parse(rawResponse, maxSubQueries);
      if (subQueries.isEmpty()) {
        log.warn(
            "Query decomposition returned no usable sub-query - falling back to single-query"
                + " retrieval");
      }
      return subQueries;
    } catch (RuntimeException e) {
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
   * ignores the prompt's instruction and emits more (#923's "max. Teilfragen konfigurierbar"
   * leitplanke). Returns an empty list for {@code null}, blank, or otherwise unusable input, which
   * {@link #decompose} treats as a decomposition failure.
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
}
