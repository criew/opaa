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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ProviderConfigurationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg18"));

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("opaa.indexing.document-path", () -> "/tmp/opaa-config-test");
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
  void chatProviderDefaultsToOpenAi() {
    assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("openai");
    assertThat(environment.getProperty("spring.ai.openai.chat.options.model")).isNotBlank();
  }

  @Test
  void embeddingProviderDefaultsToOpenAi() {
    assertThat(environment.getProperty("spring.ai.model.embedding")).isEqualTo("openai");
    assertThat(environment.getProperty("spring.ai.openai.embedding.options.model")).isNotBlank();
  }

  @Test
  void chatAndEmbeddingProvidersAreIndependent() {
    // Chat and embedding providers are separate configuration keys
    String chatProvider = environment.getProperty("spring.ai.model.chat");
    String embeddingProvider = environment.getProperty("spring.ai.model.embedding");
    assertThat(chatProvider).isNotNull();
    assertThat(embeddingProvider).isNotNull();

    // Both OpenAI and Ollama model configs coexist — switching is config-only
    assertThat(environment.getProperty("spring.ai.openai.chat.options.model")).isNotBlank();
    assertThat(environment.getProperty("spring.ai.ollama.chat.options.model")).isNotBlank();
    assertThat(environment.getProperty("spring.ai.openai.embedding.options.model")).isNotBlank();
    assertThat(environment.getProperty("spring.ai.ollama.embedding.options.model")).isNotBlank();
  }

  @Test
  void ollamaConfigurationIsAvailableForSwitching() {
    assertThat(environment.getProperty("spring.ai.ollama.chat.options.model"))
        .isEqualTo("phi3:mini");
    assertThat(environment.getProperty("spring.ai.ollama.embedding.options.model"))
        .isEqualTo("nomic-embed-text");
  }
}
