package io.opaa.query;

import io.opaa.indexing.metadata.MetadataFilter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.chat.messages.Message;

/**
 * The immutable input of one retrieval run: question, conversation history, search scope, metadata
 * filter and the parameters every stage reads. No stage can change any of it - that is why it is a
 * separate value from {@link RetrievalState}: the permission scope a run was started with is the
 * scope every one of its searches applies (ADR-0008 §5).
 *
 * <p>{@code searchScope} is taken as given; this type resolves no permissions of its own. {@code
 * metadataFilter} is the filter the asking person or the chat set, never derived from the question.
 * {@code queryProperties} and {@code rerankAvailability} travel here rather than being injected, so
 * one instance serves several parameter sets and every stage of a run sees the same rerank answer -
 * deciding it per stage would let the fusion widen its budget for a reranker the rerank stage then
 * finds unavailable. Every construction must state the availability; no constructor fills it in.
 */
public record RetrievalContext(
    String question,
    List<Message> conversationHistory,
    Set<UUID> searchScope,
    MetadataFilter metadataFilter,
    QueryProperties queryProperties,
    RerankAvailability rerankAvailability) {

  public RetrievalContext {
    conversationHistory = List.copyOf(conversationHistory);
    searchScope = Set.copyOf(searchScope);
    metadataFilter = metadataFilter == null ? MetadataFilter.NONE : metadataFilter;
  }

  /**
   * The same run with reranking taken out of the picture - used by {@link RetrievalPipeline} when
   * {@link RetrievalStageName#RERANK} is switched off for that pipeline, so the narrowing stages
   * cannot widen their budget for a stage that will never restore the {@code top-k} cap.
   */
  RetrievalContext withoutReranking() {
    return rerankAvailability == RerankAvailability.SWITCHED_OFF
        ? this
        : new RetrievalContext(
            question,
            conversationHistory,
            searchScope,
            metadataFilter,
            queryProperties,
            RerankAvailability.SWITCHED_OFF);
  }

  /**
   * Whether {@link RetrievalStageName#RERANK} actually reranks in this run: the model role must be
   * usable and the candidate window must be non-zero. Both halves are needed - the role expresses
   * the installation's intent and readiness, the window the retrieval parameter.
   */
  public boolean rerankActive() {
    return rerankAvailability == RerankAvailability.USABLE
        && queryProperties.rerankCandidateCount() > 0;
  }

  /**
   * The number of candidates the narrowing stages before the reranker keep - {@link
   * RetrievalStageName#MMR_SELECTION} per list, {@link RetrievalStageName#RANK_FUSION} overall: the
   * rerank candidate window while reranking is active, {@code top-k} otherwise. The cap is restored
   * either way, because {@link RetrievalStageName#RERANK} never hands on more than {@code top-k},
   * including when the endpoint fails mid-run.
   */
  public int candidateBudget() {
    return rerankActive()
        ? Math.max(queryProperties.topK(), queryProperties.rerankCandidateCount())
        : queryProperties.topK();
  }
}
