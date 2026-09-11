package io.opaa.query.answer;

import io.opaa.query.QueryProperties;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The conversation memory's bean wiring: {@link ChatMemory}/{@link MessageWindowChatMemory} are
 * Spring AI framework types assembled via a builder, so they need a factory method rather than a
 * {@code @Service} annotation.
 */
@Configuration
public class ConversationMemoryConfiguration {

  /**
   * The window width is {@link QueryProperties#conversationWindowMessages()} and lives nowhere
   * else: this bean is the only thing that bounds a conversation, so anything measuring the window
   * measures the configured value rather than a constant copied alongside it.
   */
  @Bean
  ChatMemory chatMemory(
      ChatMemoryRepository chatMemoryRepository, QueryProperties queryProperties) {
    return MessageWindowChatMemory.builder()
        .chatMemoryRepository(chatMemoryRepository)
        .maxMessages(queryProperties.conversationWindowMessages())
        .build();
  }
}
