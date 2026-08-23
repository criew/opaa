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
 * Verifies the connection paths application.yml wires: chat is fixed to {@code openai} since #762
 * (Ollama's OpenAI-compatible protocol, with a default base URL that already points at a locally
 * operated Ollama server - see docs/features/llm-integration.md#ein-anbindungsweg-nicht-zwei);
 * embedding is fixed to {@code ollama} since #773, reverted from that same {@code /v1/embeddings}
 * endpoint after it caused a measurable retrieval-quality regression against the eval baseline
 * (issue #773).
 *
 * <p><b>Since #758, {@code spring.ai.model.chat} no longer selects a Spring Boot autoconfiguration
 * for the chat {@code ChatModel} bean</b> - {@code application.yml} excludes {@code
 * OpenAiChatAutoConfiguration} outright, because {@code io.opaa.llm.ActiveChatModelResolver} builds
 * every chat client itself, programmatically, from the active {@code llm_models} row. The property
 * still has a real, narrower purpose: {@code io.opaa.config.OpenAiBaseUrlGuard} still reads it to
 * decide whether {@code spring.ai.openai.chat.base-url} must be set - that guard is unrelated to
 * autoconfiguration and still matters because {@code io.opaa.llm.LlmModelSeeder} reads {@code
 * spring.ai.openai.chat.*} directly for the one-time takeover into {@code llm_models} on first
 * start (docs/deployment.md, section "LLM-Anbieter"). {@code @MockitoBean private ChatModel
 * chatModel} below therefore no longer replaces a bean the excluded autoconfiguration would have
 * produced; it exists only so this test's own {@code Environment} assertions do not need a real,
 * reachable chat endpoint to load the application context.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class ProviderConfigurationTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

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
  void chatIsFixedToOpenAiAndEmbeddingIsFixedToOllama() {
    assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("openai");
    assertThat(environment.getProperty("spring.ai.model.embedding")).isEqualTo("ollama");
  }

  @Test
  void chatAndEmbeddingModelsHaveIndependentDefaults() {
    assertThat(environment.getProperty("spring.ai.openai.chat.model")).isEqualTo("phi3:mini");
    assertThat(environment.getProperty("spring.ai.ollama.embedding.model"))
        .isEqualTo("nomic-embed-text");
  }

  @Test
  void baseUrlDefaultsToALocallyOperatedOllamaServer() {
    // No profile activates "local"/"docker" here (only "dev"), so this is the top-level default -
    // the same address the "local" profile documents explicitly for a bootRun/host setup.
    assertThat(environment.getProperty("spring.ai.openai.base-url"))
        .isEqualTo("http://localhost:11434/v1");
    assertThat(environment.getProperty("spring.ai.openai.chat.base-url"))
        .isEqualTo("http://localhost:11434/v1");
    assertThat(environment.getProperty("spring.ai.ollama.base-url"))
        .isEqualTo("http://localhost:11434");
  }
}
