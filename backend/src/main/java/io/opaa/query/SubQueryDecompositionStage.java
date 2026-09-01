package io.opaa.query;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Component;

/**
 * Step 2 of docs/features/retrieval-algorithm.md as a pipeline stage: produces the search queries
 * the search stages run, one search call each.
 *
 * <p>{@link QueryDecompositionService#decompose} returns 1 to {@link QueryProperties#maxSubQueries}
 * self-contained queries, or an empty list on any failure - which falls back to {@link
 * #buildSearchQuery}'s pre-#923 single-query behaviour unchanged. {@link
 * QueryProperties#queryDecompositionEnabled} {@code = false} skips the LLM round trip and takes
 * that same fallback.
 *
 * <p>Touches no candidates: at this point in the run there are none.
 */
@Component
class SubQueryDecompositionStage implements RetrievalStage {

  private static final Logger log = LoggerFactory.getLogger(SubQueryDecompositionStage.class);

  private final QueryDecompositionService queryDecompositionService;

  SubQueryDecompositionStage(QueryDecompositionService queryDecompositionService) {
    this.queryDecompositionService = queryDecompositionService;
  }

  @Override
  public RetrievalStageName name() {
    return RetrievalStageName.SUB_QUERY_DECOMPOSITION;
  }

  @Override
  public StageOutcome apply(RetrievalContext context, RetrievalState state) {
    QueryProperties properties = context.queryProperties();
    List<String> subQueries =
        properties.queryDecompositionEnabled()
            ? queryDecompositionService.decompose(
                context.question(), context.conversationHistory(), properties.maxSubQueries())
            : List.of();
    boolean decomposed = !subQueries.isEmpty();
    List<String> searchQueries =
        decomposed
            ? subQueries
            : List.of(buildSearchQuery(context.question(), context.conversationHistory()));

    List<String> notes = new ArrayList<>();
    if (decomposed) {
      notes.add(
          "decomposition produced "
              + searchQueries.size()
              + (searchQueries.size() == 1 ? " sub-query" : " sub-queries"));
    } else if (properties.queryDecompositionEnabled()) {
      notes.add("decomposition returned nothing (failed or unparsable): single-query fallback");
    } else {
      notes.add("decomposition switched off by configuration: single-query fallback");
    }
    searchQueries.forEach(query -> notes.add("search query: " + query));

    return new StageOutcome(
        state.withSearchQueries(searchQueries),
        StageExplanation.executed(name(), 0, 0, List.of(), notes));
  }

  /**
   * The pre-#923 fallback search query: the plain {@code question}, or - when a conversation is
   * under way - the first user message of {@code history} prepended to it. {@code history} is
   * passed in rather than read from the chat memory here, so a caller that already holds it (to
   * also feed {@link QueryDecompositionService#decompose}) does not pay for a second, redundant
   * lookup.
   */
  static String buildSearchQuery(String question, List<Message> history) {
    if (history.isEmpty()) {
      return question;
    }

    String firstUserMessage = null;
    for (Message message : history) {
      if (message.getMessageType() == MessageType.USER) {
        firstUserMessage = message.getText();
        break;
      }
    }

    if (firstUserMessage == null) {
      return question;
    }

    log.debug(
        "Enriching search query with conversation context: '{}' -> '{} {}'",
        question,
        firstUserMessage,
        question);
    return firstUserMessage + " " + question;
  }
}
