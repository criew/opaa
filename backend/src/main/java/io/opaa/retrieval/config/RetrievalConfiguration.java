package io.opaa.retrieval.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.observability.QueryMetrics;
import io.opaa.retrieval.QueryProperties;
import io.opaa.retrieval.RetrievalPipeline;
import io.opaa.retrieval.RetrievalPipelineProperties;
import io.opaa.retrieval.VectorIndexScanProperties;
import io.opaa.retrieval.ranking.DocumentCompletionStage;
import io.opaa.retrieval.ranking.MmrSelectionStage;
import io.opaa.retrieval.ranking.RankFusionStage;
import io.opaa.retrieval.ranking.RerankStage;
import io.opaa.retrieval.scope.MetadataFilterStage;
import io.opaa.retrieval.scope.SearchScopeStage;
import io.opaa.retrieval.search.FullTextSearchStage;
import io.opaa.retrieval.search.SubQueryDecompositionStage;
import io.opaa.retrieval.search.VectorSearchStage;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The retrieval package's bean wiring: the pipeline's stage order and the query metrics. Everything
 * the package can annotate with {@code @Service} or {@code @Component} is wired there instead; what
 * remains needs a factory method. It lives in a subpackage of its own because it names every stage,
 * and the stages depend on the frame in {@code io.opaa.retrieval}.
 */
@Configuration
@EnableConfigurationProperties({
  QueryProperties.class,
  RetrievalPipelineProperties.class,
  VectorIndexScanProperties.class
})
public class RetrievalConfiguration {

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
