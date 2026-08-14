package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OpenAiBaseUrlGuardTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(OpenAiBaseUrlGuard.class);

  @Test
  void startsWithTheLocalDefaultsAndNoAdditionalConfiguration() {
    contextRunner
        .withPropertyValues("spring.ai.model.chat=ollama", "spring.ai.model.embedding=ollama")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void refusesToStartWhenTheChatProviderIsOpenAiWithoutABaseUrl() {
    contextRunner
        .withPropertyValues("spring.ai.model.chat=openai", "spring.ai.model.embedding=ollama")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("The chat provider is set to \"openai\"")
                    .hasMessageContaining("OPAA_OPENAI_BASE_URL")
                    .hasMessageContaining("OPAA_OPENAI_CHAT_BASE_URL"));
  }

  @Test
  void refusesToStartWhenTheEmbeddingProviderIsOpenAiWithoutABaseUrl() {
    contextRunner
        .withPropertyValues("spring.ai.model.chat=ollama", "spring.ai.model.embedding=openai")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("The embedding provider is set to \"openai\"")
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
            "spring.ai.model.chat=ollama",
            "spring.ai.model.embedding=openai",
            "spring.ai.openai.embedding.base-url=http://model-server.internal:8000/v1")
        .run(context -> assertThat(context).hasNotFailed());
  }
}
