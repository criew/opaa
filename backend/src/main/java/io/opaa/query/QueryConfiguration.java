package io.opaa.query;

import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.observability.QueryMetrics;
import io.opaa.query.answer.ConversationMemoryConfiguration;
import io.opaa.query.filter.MetadataFilterProperties;
import io.opaa.query.retrieval.RetrievalPipeline;
import io.opaa.query.retrieval.RetrievalPipelineProperties;
import io.opaa.query.retrieval.ranking.DocumentCompletionStage;
import io.opaa.query.retrieval.ranking.MmrSelectionStage;
import io.opaa.query.retrieval.ranking.RankFusionStage;
import io.opaa.query.retrieval.ranking.RerankStage;
import io.opaa.query.retrieval.scope.MetadataFilterStage;
import io.opaa.query.retrieval.scope.SearchScopeStage;
import io.opaa.query.retrieval.search.FullTextSearchStage;
import io.opaa.query.retrieval.search.SubQueryDecompositionStage;
import io.opaa.query.retrieval.search.VectorSearchStage;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The query package's bean wiring: the retrieval pipeline's stage order and the query metrics.
 * Everything the package can annotate with {@code @Service} is wired there instead; what remains
 * needs a factory method - {@link #queryMetrics} follows the project-wide convention of building a
 * metrics facade in its package's configuration. The chat memory is wired in {@link
 * ConversationMemoryConfiguration}.
 */
@Configuration
@EnableConfigurationProperties({
  QueryProperties.class,
  RetrievalPipelineProperties.class,
  MetadataFilterProperties.class
})
public class QueryConfiguration {

  /**
   * The one place the retrieval order is decided. The stages are {@code @Component}s, but their
   * sequence is not left to component scanning or to {@code @Order} annotations spread over nine
   * files; a new stage is inserted here, at the position it belongs to.
   */
  @Bean
  public RetrievalPipeline retrievalPipeline(
      SearchScopeStage searchScopeStage,
      MetadataFilterStage metadataFilterStage,
      SubQueryDecompositionStage subQueryDecompositionStage,
      VectorSearchStage vectorSearchStage,
      FullTextSearchStage fullTextSearchStage,
      MmrSelectionStage mmrSelectionStage,
      RankFusionStage rankFusionStage,
      RerankStage rerankStage,
      DocumentCompletionStage documentCompletionStage,
      RetrievalPipelineProperties pipelineProperties) {
    return new RetrievalPipeline(
        List.of(
            searchScopeStage,
            metadataFilterStage,
            subQueryDecompositionStage,
            vectorSearchStage,
            fullTextSearchStage,
            mmrSelectionStage,
            rankFusionStage,
            rerankStage,
            documentCompletionStage),
        pipelineProperties);
  }

  @Bean
  QueryMetrics queryMetrics(MeterRegistry meterRegistry) {
    return new QueryMetrics(meterRegistry);
  }
}
