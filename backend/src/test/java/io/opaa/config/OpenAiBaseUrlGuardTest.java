package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

class OpenAiBaseUrlGuardTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(OpenAiBaseUrlGuard.class);

  @Test
  void startsWithTheLocalDefaultsAndNoAdditionalConfiguration() {
    // #762: application.yml no longer knows a second, non-openai provider literal - both
    // functions are fixed to "openai" there. This guard's own check stays generic on purpose (any
    // value other than exactly "openai" is skipped), so it is exercised here with an arbitrary
    // non-openai value rather than asserting anything about the removed "ollama" literal.
    contextRunner
        .withPropertyValues("spring.ai.model.chat=none", "spring.ai.model.embedding=none")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void startsWithTheProductionDefaultBaseUrl() {
    // Mirrors what application.yml actually configures since #762: both functions fixed to
    // "openai", with a base URL already populated by its own default (Ollama's OpenAI-compatible
    // endpoint) rather than left unset - the everyday case, not the exception this guard exists
    // for.
    contextRunner
        .withPropertyValues(
            "spring.ai.model.chat=openai",
            "spring.ai.model.embedding=openai",
            "spring.ai.openai.chat.base-url=http://localhost:11434/v1",
            "spring.ai.openai.embedding.base-url=http://localhost:11434/v1")
        .run(context -> assertThat(context).hasNotFailed());
  }

  /**
   * The embedding base URL is a live runtime address, and a failed embedding call carries it into
   * the log: Spring's {@code ResourceAccessException} names the target URI, and {@code
   * FileProcessingService} logs the whole stack trace. Refused at startup, and the refusal itself
   * must not repeat the address.
   */
  @Test
  void refusesToStartWhenTheEmbeddingBaseUrlCarriesCredentials() {
    contextRunner
        .withPropertyValues(
            "spring.ai.model.chat=none",
            "spring.ai.model.embedding=openai",
            "spring.ai.openai.embedding.base-url=https://benutzer:geheim@modellserver.example.internal/v1")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("carries credentials")
                    .hasMessageContaining("embedding")
                    .hasMessageContaining("OPAA_OPENAI_EMBEDDING_API_KEY")
                    .hasMessageNotContaining("geheim")
                    .hasMessageNotContaining("modellserver.example.internal"));
  }

  @Test
  void refusesToStartWhenTheChatBaseUrlCarriesCredentials() {
    contextRunner
        .withPropertyValues(
            "spring.ai.model.chat=openai",
            "spring.ai.model.embedding=none",
            "spring.ai.openai.chat.base-url=https://benutzer:geheim@modellserver.example.internal/v1")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("carries credentials")
                    .hasMessageContaining("OPAA_OPENAI_CHAT_API_KEY")
                    .hasMessageNotContaining("geheim"));
  }

  @Test
  void refusesToStartWhenTheChatProviderIsOpenAiWithoutABaseUrl() {
    contextRunner
        .withPropertyValues("spring.ai.model.chat=openai", "spring.ai.model.embedding=none")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("No base URL is configured for the chat function")
                    .hasMessageContaining("OPAA_OPENAI_BASE_URL")
                    .hasMessageContaining("OPAA_OPENAI_CHAT_BASE_URL"));
  }

  @Test
  void refusesToStartWhenTheEmbeddingProviderIsOpenAiWithoutABaseUrl() {
    contextRunner
        .withPropertyValues("spring.ai.model.chat=none", "spring.ai.model.embedding=openai")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("No base URL is configured for the embedding function")
                    .hasMessageContaining("OPAA_OPENAI_EMBEDDING_BASE_URL"));
  }

  @Test
  void refusesToStartWhenTheBaseUrlIsBlank() {
    contextRunner
        .withPropertyValues("spring.ai.model.chat=openai", "spring.ai.openai.chat.base-url=   ")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void startsWhenTheSelectedProviderHasABaseUrl() {
    contextRunner
        .withPropertyValues(
            "spring.ai.model.chat=openai",
            "spring.ai.model.embedding=openai",
            "spring.ai.openai.chat.base-url=http://model-server.internal:8000/v1",
            "spring.ai.openai.embedding.base-url=http://model-server.internal:8000/v1")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void ignoresAMissingBaseUrlForAFunctionThatDoesNotUseOpenAi() {
    contextRunner
        .withPropertyValues(
            "spring.ai.model.chat=none",
            "spring.ai.model.embedding=openai",
            "spring.ai.openai.embedding.base-url=http://model-server.internal:8000/v1")
        .run(context -> assertThat(context).hasNotFailed());
  }

  /**
   * Unlike the tests above - which stand up {@link OpenAiBaseUrlGuard} against property values
   * fabricated by the test itself, never against {@code application.yml} - these two actually
   * process {@code backend/src/main/resources/application.yml} as real Spring Boot config data (PR
   * #766 review, Befund 7a): a real {@link SpringApplicationBuilder} run (not {@link
   * ApplicationContextRunner}, which never touches config data files at all) resolves {@code
   * ${VAR:default}} placeholders and multi-document profile sections exactly like a real
   * application startup does. {@code web(WebApplicationType.NONE)} and registering only {@link
   * OpenAiBaseUrlGuard} itself as a source keep this cheap enough to run without Docker or a
   * database - no other bean in the application ever gets created.
   */
  private ConfigurableApplicationContext runWithRealApplicationYml(String... additionalArgs) {
    return new SpringApplicationBuilder(OpenAiBaseUrlGuard.class)
        .web(WebApplicationType.NONE)
        .run(additionalArgs);
  }

  @Test
  void startsWithTheRealApplicationYmlAndNoOverride() {
    ConfigurableApplicationContext context = runWithRealApplicationYml();
    try {
      assertThat(context.isActive()).isTrue();
    } finally {
      context.close();
    }
  }

  @Test
  void refusesToStartWithTheRealApplicationYmlWhenAnOverrideIsExplicitlyBlank() {
    // application.yml's own default base URL is never blank - only an operator-supplied, explicitly
    // empty OPAA_OPENAI_BASE_URL (e.g. an uncommented, empty line in a real .env file) reaches this
    // failure, since ${VAR:default} only falls back to default when VAR is entirely unset.
    assertThatThrownBy(() -> runWithRealApplicationYml("--OPAA_OPENAI_BASE_URL="))
        .rootCause()
        .hasMessageContaining("No base URL is configured for the chat function");
  }
}
