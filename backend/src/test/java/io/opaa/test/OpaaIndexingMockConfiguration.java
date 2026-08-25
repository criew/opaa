package io.opaa.test;

import io.opaa.FakeEmbeddingModel;
import io.opaa.llm.ActiveChatModelResolver;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The canonical mock/fake LLM set every {@link OpaaIndexingIntegrationTest} class needs: a Mockito
 * mock {@link ChatModel} and {@link ActiveChatModelResolver} (reset before every test method by
 * {@link OpaaIndexingMockResetListener}, not individually stubbed per class here), and a
 * deterministic {@link FakeEmbeddingModel} - importing this class once, instead of each test class
 * declaring its own {@code @MockitoBean}/{@code @Primary} {@code TestConfiguration}, is what keeps
 * those classes' merged configuration - and therefore the Spring context cache key - identical.
 */
@TestConfiguration
class OpaaIndexingMockConfiguration {

  @Bean
  @Primary
  ChatModel chatModel() {
    return Mockito.mock(ChatModel.class);
  }

  @Bean
  @Primary
  ActiveChatModelResolver activeChatModelResolver() {
    return Mockito.mock(ActiveChatModelResolver.class);
  }

  @Bean
  @Primary
  EmbeddingModel embeddingModel() {
    return new FakeEmbeddingModel();
  }
}
