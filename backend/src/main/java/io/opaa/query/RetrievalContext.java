package io.opaa.query;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.chat.messages.Message;

/**
 * The immutable input of one retrieval run: the question, the conversation history the
 * decomposition stage may resolve follow-ups against, the search scope, and the parameters every
 * stage reads.
 *
 * <p><b>No stage can change any of it.</b> That is the whole reason it is a separate value from
 * {@link RetrievalState}: a stage receives this context read-only, so the permission scope a run
 * was started with is the permission scope every one of its searches applies (ADR-0008 §5).
 *
 * <p>{@code searchScope} is taken as given, exactly as {@code
 * QueryService#retrieveRelevantChunksInGivenScope} takes it: this type resolves no permissions of
 * its own, and whoever builds it is responsible for the scope being one the acting user may read.
 *
 * <p>{@code queryProperties} travels in the context rather than being injected into the stages so
 * that one pipeline instance can serve several parameter sets in the same process - the
 * variant-comparison harness (issue #1041) measures a dozen of them without rebuilding the bean
 * graph.
 */
public record RetrievalContext(
    String question,
    List<Message> conversationHistory,
    Set<UUID> searchScope,
    QueryProperties queryProperties) {

  public RetrievalContext {
    conversationHistory = List.copyOf(conversationHistory);
    searchScope = Set.copyOf(searchScope);
  }
}
