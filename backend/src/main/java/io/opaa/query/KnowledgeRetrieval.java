package io.opaa.query;

import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.query.retrieval.RetrievalPipeline;
import io.opaa.query.retrieval.RetrievalPipelineResult;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

/**
 * The one entrance into the {@link RetrievalPipeline} for a caller-facing request: {@link
 * QueryService} builds its answer from what this returns, {@code io.opaa.search.SearchService}
 * returns it as hits. Both therefore run the same sub-question decomposition, the same vector and
 * full-text search, the same fusion, the same reranking and the same permission filter - a second
 * ranking path would be a second quality truth that nobody maintains (#1720,
 * docs/features/external-access.md, "Was ein Fremdzugang erreicht").
 *
 * <p>{@code searchScope} is taken as given
 * (docs/features/spaces-and-assets.md#ein-agent-liest-immer-mit-den-rechten-des-nutzers); resolving
 * it stays with the caller, which is what lets the query take a chat's own settings and the search
 * take the effective view of a request.
 */
@Component
public class KnowledgeRetrieval {

  private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrieval.class);

  private final RetrievalPipeline retrievalPipeline;
  private final RetrievalContextFactory retrievalContextFactory;

  public KnowledgeRetrieval(
      RetrievalPipeline retrievalPipeline, RetrievalContextFactory retrievalContextFactory) {
    this.retrievalPipeline = retrievalPipeline;
    this.retrievalContextFactory = retrievalContextFactory;
  }

  /**
   * Runs the whole pipeline over {@code searchScope}; the chunks come back in the order and count
   * the answer prompt would be built from. The explanation protocol is not read here - the
   * administration's diagnosis and the evaluation harness run the pipeline themselves and keep it.
   */
  public RetrievalPipelineResult retrieve(
      String question,
      List<Message> conversationHistory,
      List<String> conversationNote,
      Set<UUID> searchScope,
      MetadataFilter metadataFilter) {
    RetrievalPipelineResult result =
        retrievalPipeline.run(
            retrievalContextFactory.contextFor(
                question, conversationHistory, conversationNote, searchScope, metadataFilter));
    // Only for a run that actually searched: a "0 chunks across 0 search queries" line would
    // read like a failed retrieval rather than a run halted before any search.
    if (!result.searchQueries().isEmpty()) {
      log.debug(
          "Retrieved {} relevant chunks across {} search quer{}",
          result.chunks().size(),
          result.searchQueries().size(),
          result.searchQueries().size() == 1 ? "y" : "ies");
    }
    return result;
  }
}
