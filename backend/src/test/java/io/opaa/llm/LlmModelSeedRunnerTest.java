package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.security.SettingsEncryptor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

/**
 * Unit tests for {@link LlmModelSeedRunner} (#756,
 * docs/features/llm-integration.md#übergang-aus-der-heutigen-konfiguration) - a {@link
 * LlmModelRepository} mock lets these run without a database, covering the branching logic ({@code
 * ollama} vs. {@code openai} vs. neither) and the {@code /v1} suffix handling directly.
 */
class LlmModelSeedRunnerTest {

  private final LlmModelRepository repository = mock(LlmModelRepository.class);
  private final SettingsEncryptor settingsEncryptor = mock(SettingsEncryptor.class);

  @Test
  void doesNothingWhenAModelAlreadyExists() {
    when(repository.count()).thenReturn(1L);
    MockEnvironment environment =
        new MockEnvironment().withProperty("spring.ai.model.chat", "ollama");
    LlmModelSeedRunner runner = new LlmModelSeedRunner(repository, settingsEncryptor, environment);

    runner.run(null);

    verify(repository, never()).save(any());
  }

  @Test
  void seedsAnActiveModelWithoutAKeyFromTheOllamaConfiguration() {
    when(repository.count()).thenReturn(0L);
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("spring.ai.model.chat", "ollama")
            .withProperty("spring.ai.ollama.base-url", "http://ollama:11434")
            .withProperty("spring.ai.ollama.chat.model", "phi3:mini");
    LlmModelSeedRunner runner = new LlmModelSeedRunner(repository, settingsEncryptor, environment);

    runner.run(null);

    ArgumentCaptor<LlmModel> captor = ArgumentCaptor.forClass(LlmModel.class);
    verify(repository, times(1)).save(captor.capture());
    LlmModel saved = captor.getValue();
    assertThat(saved.getBaseUrl()).isEqualTo("http://ollama:11434/v1");
    assertThat(saved.getModelIdentifier()).isEqualTo("phi3:mini");
    assertThat(saved.getApiKeyCiphertext()).isNull();
    assertThat(saved.isActive()).isTrue();
  }

  @Test
  void doesNotDoubleAnAlreadyPresentV1Suffix() {
    when(repository.count()).thenReturn(0L);
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("spring.ai.model.chat", "ollama")
            .withProperty("spring.ai.ollama.base-url", "http://ollama:11434/v1")
            .withProperty("spring.ai.ollama.chat.model", "phi3:mini");
    LlmModelSeedRunner runner = new LlmModelSeedRunner(repository, settingsEncryptor, environment);

    runner.run(null);

    ArgumentCaptor<LlmModel> captor = ArgumentCaptor.forClass(LlmModel.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getBaseUrl()).isEqualTo("http://ollama:11434/v1");
  }

  @Test
  void seedsFromTheOpenAiConfigurationIncludingTheEncryptedApiKey() {
    when(repository.count()).thenReturn(0L);
    when(settingsEncryptor.encrypt("sk-configured-key")).thenReturn("enc:v1:ciphertext");
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("spring.ai.model.chat", "openai")
            .withProperty(
                "spring.ai.openai.chat.base-url", "https://modellserver.example.internal/v1")
            .withProperty("spring.ai.openai.chat.model", "gpt-4o")
            .withProperty("spring.ai.openai.chat.temperature", "0.5")
            .withProperty("spring.ai.openai.chat.max-tokens", "1500")
            .withProperty("spring.ai.openai.chat.api-key", "sk-configured-key");
    LlmModelSeedRunner runner = new LlmModelSeedRunner(repository, settingsEncryptor, environment);

    runner.run(null);

    ArgumentCaptor<LlmModel> captor = ArgumentCaptor.forClass(LlmModel.class);
    verify(repository).save(captor.capture());
    LlmModel saved = captor.getValue();
    assertThat(saved.getBaseUrl()).isEqualTo("https://modellserver.example.internal/v1");
    assertThat(saved.getModelIdentifier()).isEqualTo("gpt-4o");
    assertThat(saved.getTemperature()).isEqualByComparingTo("0.5");
    assertThat(saved.getMaxTokens()).isEqualTo(1500);
    assertThat(saved.getApiKeyCiphertext()).isEqualTo("enc:v1:ciphertext");
    assertThat(saved.isActive()).isTrue();
  }

  @Test
  void seedsNothingAndStillStartsWhenNeitherOllamaNorOpenAiIsConfigured() {
    when(repository.count()).thenReturn(0L);
    MockEnvironment environment =
        new MockEnvironment().withProperty("spring.ai.model.chat", "something-else");
    LlmModelSeedRunner runner = new LlmModelSeedRunner(repository, settingsEncryptor, environment);

    runner.run(null);

    verify(repository, never()).save(any());
  }

  @Test
  void ensureV1SuffixAppendsExactlyOnce() {
    assertThat(LlmModelSeedRunner.ensureV1Suffix("http://ollama:11434"))
        .isEqualTo("http://ollama:11434/v1");
    assertThat(LlmModelSeedRunner.ensureV1Suffix("http://ollama:11434/v1"))
        .isEqualTo("http://ollama:11434/v1");
    assertThat(LlmModelSeedRunner.ensureV1Suffix("http://ollama:11434/v1/"))
        .isEqualTo("http://ollama:11434/v1");
    assertThat(LlmModelSeedRunner.ensureV1Suffix("http://ollama:11434/"))
        .isEqualTo("http://ollama:11434/v1");
  }
}
