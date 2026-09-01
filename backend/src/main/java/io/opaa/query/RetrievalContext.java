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
 *
 * <p>{@code rerankRoleUsable} travels here for the same reason: whether the rerank model role can
 * be called is decided once per run, by whoever builds the context, and every stage that depends on
 * it must see the same answer. Deciding it per stage would let {@link
 * RetrievalStageName#RANK_FUSION} widen its budget for a reranker that {@link
 * RetrievalStageName#RERANK} then finds unavailable.
 */
public record RetrievalContext(
    String question,
    List<Message> conversationHistory,
    Set<UUID> searchScope,
    QueryProperties queryProperties,
    boolean rerankRoleUsable) {

  public RetrievalContext {
    conversationHistory = List.copyOf(conversationHistory);
    searchScope = Set.copyOf(searchScope);
  }

  /**
   * A run without a usable rerank model role - the shipped configuration, in which {@code
   * OPAA_RERANK_ENABLED} is off.
   */
  public RetrievalContext(
      String question,
      List<Message> conversationHistory,
      Set<UUID> searchScope,
      QueryProperties queryProperties) {
    this(question, conversationHistory, searchScope, queryProperties, false);
  }

  /**
   * Whether {@link RetrievalStageName#RERANK} actually reranks in this run: the model role must be
   * usable and the candidate window must be non-zero. Both halves are needed - the role expresses
   * the installation's intent and readiness, the window the retrieval parameter.
   */
  public boolean rerankActive() {
    return rerankRoleUsable && queryProperties.rerankCandidateCount() > 0;
  }

  /**
   * The number of candidates the narrowing stages before the reranker keep - {@link
   * RetrievalStageName#MMR_SELECTION} per list, {@link RetrievalStageName#RANK_FUSION} overall.
   * With reranking active that is the rerank candidate window, because the reranker is then the
   * stage that decides the final {@code top-k}; without it, {@code top-k} itself, which is exactly
   * what those stages used before reranking existed. The cap is restored either way: {@link
   * RetrievalStageName#RERANK} never hands on more than {@code top-k}, including when the endpoint
   * fails mid-run.
   */
  public int candidateBudget() {
    return rerankActive()
        ? Math.max(queryProperties.topK(), queryProperties.rerankCandidateCount())
        : queryProperties.topK();
  }
}
