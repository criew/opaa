package io.opaa.query;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!mock")
public class QueryConfiguration {

  @Bean
  AnswerGenerationService answerGenerationService(ChatModel chatModel) {
    return new AnswerGenerationService(chatModel);
  }

  @Bean
  CitationParser citationParser() {
    return new CitationParser();
  }

  @Bean
  QueryService queryService(
      VectorStore vectorStore,
      AnswerGenerationService answerGenerationService,
      CitationParser citationParser) {
    return new QueryService(vectorStore, answerGenerationService, citationParser);
  }
}
