package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.FakeEmbeddingModel;
import io.opaa.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies that the application context loads when chat and embedding point at different
 * openai-compatible base URLs - since #762 there is only one provider ({@code openai}), but chat
 * and embedding remain independently configurable functions (different endpoints, different
 * models).
 *
 * <p>The embedding base URL is stated explicitly here even though {@code application.yml} already
 * defaults it to a local Ollama endpoint, to prove the override actually takes effect. The address
 * below is never called — the embedding model is replaced by {@link FakeEmbeddingModel}.
 */
// Own context (the @SpringBootTest properties override below is the subject under test), shared
// TestcontainersConfiguration container config.
@SpringBootTest(
    properties = {"spring.ai.openai.embedding.base-url=http://model-server.invalid:8000/v1"})
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class MixedProviderConfigurationTest {

  @TestConfiguration
  static class TestConfig {
    @Bean
    @Primary
    EmbeddingModel testEmbeddingModel() {
      return new FakeEmbeddingModel();
    }
  }

  @MockitoBean private ChatModel chatModel;

  @Autowired private Environment environment;

  @Test
  void contextLoadsWithDifferentChatAndEmbeddingBaseUrls() {
    assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("openai");
    assertThat(environment.getProperty("spring.ai.model.embedding")).isEqualTo("openai");
    assertThat(environment.getProperty("spring.ai.openai.chat.base-url"))
        .isEqualTo("http://localhost:11434/v1");
    assertThat(environment.getProperty("spring.ai.openai.embedding.base-url"))
        .isEqualTo("http://model-server.invalid:8000/v1");
  }
}
