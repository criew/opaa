package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
}
