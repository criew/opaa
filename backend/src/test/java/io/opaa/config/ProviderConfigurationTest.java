package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.OpaaIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * Verifies the single, OpenAI-compatible connection path application.yml wires: chat is fixed to
 * {@code openai}, with a default base URL that already points at a locally operated Ollama server,
 * and the embedding model is the installed one ({@code none} as autoconfiguration) - no separate,
 * native provider path exists anymore (see
 * docs/features/llm-integration.md#ein-anbindungsweg-nicht-zwei).
 *
 * <p><b>{@code spring.ai.model.chat} selects no Spring Boot autoconfiguration for the chat {@code
 * ChatModel} bean</b> - {@code application.yml} excludes {@code OpenAiChatAutoConfiguration}
 * outright, because {@code io.opaa.llm.ActiveChatModelResolver} builds every chat client itself,
 * programmatically, from the active {@code llm_models} row. The property still has a real, narrower
 * purpose: {@code io.opaa.config.OpenAiBaseUrlGuard} still reads it to decide whether {@code
 * spring.ai.openai.base-url} must be set - the address the {@code dev} mode installs its chat model
 * with (ADR-0060). The chat models themselves come from the installation. {@code @MockitoBean
 * private ChatModel} bean of the shared test configuration therefore no longer replaces a bean the
 * excluded autoconfiguration would have produced; it exists only so a test's {@code Environment}
 * assertions do not need a real, reachable chat endpoint to load the application context.
 */
@OpaaIntegrationTest
class ProviderConfigurationTest {

  @Autowired private Environment environment;

  @Test
  void chatIsFixedToTheOpenAiCompatibleProtocolAndEmbeddingIsNoAutoconfiguration() {
    assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("openai");
    assertThat(environment.getProperty("spring.ai.model.embedding")).isEqualTo("none");
  }

  @Test
  void neitherTheChatNorTheEmbeddingModelIsAConfigurationValue() {
    assertThat(environment.getProperty("spring.ai.openai.chat.model")).isNull();
    assertThat(environment.getProperty("spring.ai.openai.embedding.model")).isNull();
  }

  /** OPAA_OPENAI_EMBEDDING_TIMEOUT is the per-call timeout of every embedding client. */
  @Test
  void theEmbeddingTimeoutHasItsOwnVariableWithADefaultForBgeM3OnCpu() {
    assertThat(environment.getProperty("spring.ai.openai.embedding.timeout")).isEqualTo("180s");
  }

  @Test
  void theSharedAndTheEmbeddingBaseUrlAreTheTestSignaturesAddressesThatNeverAnswer() {
    // The application.yml defaults of both are checked by ModelBaseUrlDefaultsTest.
    assertThat(environment.getProperty("spring.ai.openai.base-url"))
        .isEqualTo(OpaaIntegrationTest.CHAT_BASE_URL);
    assertThat(environment.getProperty("spring.ai.openai.embedding.base-url"))
        .isEqualTo(OpaaIntegrationTest.EMBEDDING_BASE_URL);
  }
}
