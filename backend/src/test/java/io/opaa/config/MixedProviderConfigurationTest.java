package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.FakeEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifies that the application context loads when chat and embedding point at different servers
 * through two genuinely different providers - {@code openai} (Ollama's OpenAI-compatible protocol)
 * for chat since #762, {@code ollama} (its native API) for embedding since #773, which reverted
 * embedding off the {@code openai} protocol after it caused a measurable retrieval-quality
 * regression against the eval baseline (issue #773).
 *
 * <p>The embedding base URL is stated explicitly here even though {@code application.yml} already
 * defaults it to a local Ollama endpoint, to prove the override actually takes effect. The address
 * below is never called — the embedding model is replaced by {@link FakeEmbeddingModel}.
 */
@SpringBootTest(properties = {"spring.ai.ollama.base-url=http://model-server.invalid:8000"})
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class MixedProviderConfigurationTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("opaa.indexing.document-path", () -> "/tmp/opaa-mixed-config-test");
  }

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
    assertThat(environment.getProperty("spring.ai.model.embedding")).isEqualTo("ollama");
    assertThat(environment.getProperty("spring.ai.openai.chat.base-url"))
        .isEqualTo("http://localhost:11434/v1");
    assertThat(environment.getProperty("spring.ai.ollama.base-url"))
        .isEqualTo("http://model-server.invalid:8000");
  }
}
