package io.opaa.query;

import io.opaa.indexing.DocumentRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!mock")
public class QueryConfiguration {

  @Bean
  ChatMemory chatMemory() {
    return MessageWindowChatMemory.builder().build();
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
  QueryService queryService(
      VectorStore vectorStore,
      AnswerGenerationService answerGenerationService,
      ChatMemory chatMemory,
      CitationParser citationParser,
      DocumentRepository documentRepository) {
    return new QueryService(
        vectorStore, answerGenerationService, chatMemory, citationParser, documentRepository);
  }
}
