package io.opaa.query;

import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.observability.QueryMetrics;
import java.util.List;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The query package's bean wiring: the retrieval pipeline's stage order, the chat memory and the
 * query metrics. Everything the package can annotate with {@code @Service} is wired there instead;
 * what remains needs a factory method - {@link ChatMemory}/{@link MessageWindowChatMemory} are
 * Spring AI framework types assembled via a builder, and {@link #queryMetrics} follows the
 * project-wide convention of building a metrics facade in its package's configuration.
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
  RetrievalPipeline retrievalPipeline(
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

  /**
   * Maximum messages retained per conversation. Default 20: this corresponds to roughly 10
   * question/answer pairs, limiting the context window tokens sent to the LLM while preserving
   * enough history for coherent multi-turn dialogues.
   */
  static final int MAX_MESSAGES_PER_CONVERSATION = 20;

  @Bean
  ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
    return MessageWindowChatMemory.builder()
        .chatMemoryRepository(chatMemoryRepository)
        .maxMessages(MAX_MESSAGES_PER_CONVERSATION)
        .build();
  }

  @Bean
  QueryMetrics queryMetrics(MeterRegistry meterRegistry) {
    return new QueryMetrics(meterRegistry);
  }
}
