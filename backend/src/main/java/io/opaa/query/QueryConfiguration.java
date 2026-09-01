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
 * The genuine configuration left in the query pipeline after #889 (O2) moved every other previously
 * manually-wired bean here to an {@code @Service} on the class itself ({@link
 * CaffeineChatMemoryRepository}, {@link AnswerGenerationService}, {@link CitationParser}, {@link
 * CitationValidator}, {@link QueryService}) - see each class's own Javadoc. {@link #chatMemory}
 * stays a {@code @Bean} factory method because {@link ChatMemory}/{@link MessageWindowChatMemory}
 * are Spring AI framework types assembled via a builder, not application classes this codebase
 * owns; {@link #queryMetrics} stays wired the same way {@code AuthMetrics}/{@code IndexingMetrics}
 * are in their own {@code *Configuration} classes project-wide, a deliberate, consistent
 * cross-cutting convention this issue did not change.
 */
@Configuration
@EnableConfigurationProperties({QueryProperties.class, RetrievalPipelineProperties.class})
public class QueryConfiguration {

  /**
   * <b>The one place the retrieval order is decided</b> (docs/features/hybrid-retrieval.md,
   * Arbeitspaket 1). The stages are {@code @Component}s, but their sequence is not left to
   * component scanning or to {@code @Order} annotations scattered across six files: whether
   * reranking runs before or after document completion is a technical decision with consequences,
   * and it belongs somewhere it can be read off in one line.
   *
   * <p>New stages are inserted here, at the position they belong to - the lexical search path next
   * to {@link VectorSearchStage}, reranking between {@link RankFusionStage} and {@link
   * DocumentCompletionStage} (docs/features/hybrid-retrieval.md, Arbeitspaket 2/4).
   */
  @Bean
  RetrievalPipeline retrievalPipeline(
      SearchScopeStage searchScopeStage,
      SubQueryDecompositionStage subQueryDecompositionStage,
      VectorSearchStage vectorSearchStage,
      MmrSelectionStage mmrSelectionStage,
      RankFusionStage rankFusionStage,
      DocumentCompletionStage documentCompletionStage,
      RetrievalPipelineProperties pipelineProperties) {
    return new RetrievalPipeline(
        List.of(
            searchScopeStage,
            subQueryDecompositionStage,
            vectorSearchStage,
            mmrSelectionStage,
            rankFusionStage,
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
