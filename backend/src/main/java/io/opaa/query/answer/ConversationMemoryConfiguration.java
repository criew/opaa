package io.opaa.query.answer;

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
}
