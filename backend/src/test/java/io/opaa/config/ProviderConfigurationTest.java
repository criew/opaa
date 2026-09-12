package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.OpaaIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * Verifies the single, OpenAI-compatible connection path application.yml wires since #762: both
 * chat and embedding are fixed to {@code openai}, with a default base URL that already points at a
 * locally operated Ollama server - no separate, native provider path exists anymore (see
 * docs/features/llm-integration.md#ein-anbindungsweg-nicht-zwei).
 *
 * <p><b>Since #758, {@code spring.ai.model.chat} no longer selects a Spring Boot autoconfiguration
 * for the chat {@code ChatModel} bean</b> - {@code application.yml} excludes {@code
 * OpenAiChatAutoConfiguration} outright, because {@code io.opaa.llm.ActiveChatModelResolver} builds
 * every chat client itself, programmatically, from the active {@code llm_models} row. The property
 * still has a real, narrower purpose: {@code io.opaa.config.OpenAiBaseUrlGuard} still reads it to
 * decide whether {@code spring.ai.openai.chat.base-url} must be set - that guard is unrelated to
 * autoconfiguration and still matters because {@code io.opaa.llm.LlmModelSeeder} reads {@code
 * spring.ai.openai.chat.*} directly for the one-time takeover into {@code llm_models} on first
 * start (docs/handbuch/deployment.md, section "LLM-Anbieter"). {@code @MockitoBean private
 * ChatModel} bean of the shared test configuration therefore no longer replaces a bean the excluded
 * autoconfiguration would have produced; it exists only so a test's {@code Environment} assertions
 * do not need a real, reachable chat endpoint to load the application context.
 */
@OpaaIntegrationTest
class ProviderConfigurationTest {

  @Autowired private Environment environment;

  @Test
  void chatAndEmbeddingAreFixedToTheOpenAiCompatibleProtocol() {
    assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("openai");
    assertThat(environment.getProperty("spring.ai.model.embedding")).isEqualTo("openai");
  }

  @Test
  void chatAndEmbeddingModelsHaveIndependentDefaults() {
    assertThat(environment.getProperty("spring.ai.openai.chat.model")).isEqualTo("phi3:mini");
    assertThat(environment.getProperty("spring.ai.openai.embedding.model"))
        .isEqualTo("nomic-embed-text");
  }

  @Test
  void baseUrlDefaultsToALocallyOperatedOllamaServer() {
    // The "local" profile the suite activates alongside "dev" overrides nothing (application.yml
    // documents it as a no-op), and "docker" is not active - so this is the top-level default.
    assertThat(environment.getProperty("spring.ai.openai.base-url"))
        .isEqualTo("http://localhost:11434/v1");
    assertThat(environment.getProperty("spring.ai.openai.chat.base-url"))
        .isEqualTo("http://localhost:11434/v1");
    assertThat(environment.getProperty("spring.ai.openai.embedding.base-url"))
        .isEqualTo("http://localhost:11434/v1");
  }
}
