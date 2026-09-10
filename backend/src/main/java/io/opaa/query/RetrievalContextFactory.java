package io.opaa.query;

import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.llm.RerankModelRole;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link RetrievalContext} of one run from the production {@link QueryProperties} and
 * the rerank model role's current state - the one place every caller of {@link RetrievalPipeline}
 * (the chat query, the administration's diagnosis, the evaluation harness) obtains its context, so
 * none of them can differ from the real search in a parameter.
 */
@Component
public class RetrievalContextFactory {

  private final QueryProperties queryProperties;
  private final RerankModelRole rerankModelRole;

  public RetrievalContextFactory(QueryProperties queryProperties, RerankModelRole rerankModelRole) {
    this.queryProperties = queryProperties;
    this.rerankModelRole = rerankModelRole;
  }

  /** The parameter set every context of this factory carries. */
  public QueryProperties queryProperties() {
    return queryProperties;
  }

  /**
   * {@code searchScope} is taken as given (ADR-0008 §5). The rerank role's state is read once per
   * call, so every stage of the run sees the same answer: fusion widens its budget for the reranker
   * only if the reranker can actually be called.
   */
  public RetrievalContext contextFor(
      String question,
      List<Message> conversationHistory,
      Set<UUID> searchScope,
      MetadataFilter metadataFilter) {
    return new RetrievalContext(
        question,
        conversationHistory,
        searchScope,
        metadataFilter,
        queryProperties,
        RerankAvailability.of(rerankModelRole.currentStatus().state()));
  }
}
