package io.opaa.query;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.indexing.DocumentRepository;
import io.opaa.observability.QueryMetrics;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(QueryProperties.class)
public class QueryConfiguration {

  /**
   * Maximum number of concurrent conversation caches. Default 50: moderate memory usage suitable
   * for typical team sizes — each conversation holds up to {@link #MAX_MESSAGES_PER_CONVERSATION}
   * messages in a Caffeine cache entry.
   */
  static final int MAX_CONVERSATIONS = 50;

  /**
   * Time-to-live in minutes for idle conversations. Default 60: one hour covers a typical user
   * session; conversations are evicted after this period of inactivity to free memory.
   */
  static final int TTL_MINUTES = 60;

  /**
   * Maximum messages retained per conversation. Default 20: this corresponds to roughly 10
   * question/answer pairs, limiting the context window tokens sent to the LLM while preserving
   * enough history for coherent multi-turn dialogues.
   */
  static final int MAX_MESSAGES_PER_CONVERSATION = 20;

  @Bean
  ChatMemoryRepository chatMemoryRepository(MeterRegistry meterRegistry) {
    CaffeineChatMemoryRepository repository =
        new CaffeineChatMemoryRepository(MAX_CONVERSATIONS, TTL_MINUTES);
    Gauge.builder("opaa.conversations.active", repository, CaffeineChatMemoryRepository::size)
        .description("Active conversations in memory")
        .register(meterRegistry);
    return repository;
  }

  @Bean
  ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
    return MessageWindowChatMemory.builder()
        .chatMemoryRepository(chatMemoryRepository)
        .maxMessages(MAX_MESSAGES_PER_CONVERSATION)
        .build();
  }

  @Bean
  AnswerGenerationService answerGenerationService(
      ChatClient.Builder chatClientBuilder, ChatMemory chatMemory) {
    return new AnswerGenerationService(chatClientBuilder, chatMemory);
  }

  @Bean
  CitationParser citationParser() {
    return new CitationParser();
  }

  @Bean
  QueryMetrics queryMetrics(MeterRegistry meterRegistry) {
    return new QueryMetrics(meterRegistry);
  }

  @Bean
  QueryService queryService(
      VectorStore vectorStore,
      AnswerGenerationService answerGenerationService,
      ChatMemory chatMemory,
      CitationParser citationParser,
      DocumentRepository documentRepository,
      QueryMetrics queryMetrics,
      QueryProperties queryProperties) {
    return new QueryService(
        vectorStore,
        answerGenerationService,
        chatMemory,
        citationParser,
        documentRepository,
        queryMetrics,
        queryProperties);
  }
}
