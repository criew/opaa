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
 * Unit tests for {@link LlmModelSeeder} (#756,
 * docs/features/llm-integration.md#übergang-aus-der-heutigen-konfiguration) - repository mocks let
 * these run without a database, covering the branching logic ({@code ollama} vs. {@code openai} vs.
 * neither), the {@code /v1} suffix handling and the seed marker directly.
 */
class LlmModelSeederTest {

  private final LlmModelRepository repository = mock(LlmModelRepository.class);
  private final LlmModelSeedMarkerRepository markerRepository =
      mock(LlmModelSeedMarkerRepository.class);
  private final SettingsEncryptor settingsEncryptor = mock(SettingsEncryptor.class);

  private LlmModelSeeder seederWith(MockEnvironment environment) {
    return new LlmModelSeeder(repository, markerRepository, settingsEncryptor, environment);
  }

  @Test
  void doesNothingWhenTheTakeoverWasAlreadyAttempted() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(true);
    MockEnvironment environment =
        new MockEnvironment().withProperty("spring.ai.model.chat", "ollama");

    seederWith(environment).seedIfNeeded();

    verify(repository, never()).save(any());
    verify(markerRepository, never()).save(any());
  }

  @Test
  void seedsAnActiveModelWithoutAKeyFromTheOllamaConfigurationAndWritesTheMarker() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("spring.ai.model.chat", "ollama")
            .withProperty("spring.ai.ollama.base-url", "http://ollama:11434")
            .withProperty("spring.ai.ollama.chat.model", "phi3:mini");

    seederWith(environment).seedIfNeeded();

    ArgumentCaptor<LlmModel> captor = ArgumentCaptor.forClass(LlmModel.class);
    verify(repository, times(1)).save(captor.capture());
    LlmModel saved = captor.getValue();
    assertThat(saved.getBaseUrl()).isEqualTo("http://ollama:11434/v1");
    assertThat(saved.getModelIdentifier()).isEqualTo("phi3:mini");
    assertThat(saved.getApiKeyCiphertext()).isNull();
    assertThat(saved.isActive()).isTrue();
    verify(markerRepository, times(1)).save(any());
  }

  @Test
  void doesNotDoubleAnAlreadyPresentV1Suffix() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("spring.ai.model.chat", "ollama")
            .withProperty("spring.ai.ollama.base-url", "http://ollama:11434/v1")
            .withProperty("spring.ai.ollama.chat.model", "phi3:mini");

    seederWith(environment).seedIfNeeded();

    ArgumentCaptor<LlmModel> captor = ArgumentCaptor.forClass(LlmModel.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getBaseUrl()).isEqualTo("http://ollama:11434/v1");
  }

  @Test
  void seedsFromTheOpenAiConfigurationIncludingTheEncryptedApiKey() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
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

    seederWith(environment).seedIfNeeded();

    ArgumentCaptor<LlmModel> captor = ArgumentCaptor.forClass(LlmModel.class);
    verify(repository).save(captor.capture());
    LlmModel saved = captor.getValue();
    assertThat(saved.getBaseUrl()).isEqualTo("https://modellserver.example.internal/v1");
    assertThat(saved.getModelIdentifier()).isEqualTo("gpt-4o");
    assertThat(saved.getTemperature()).isEqualByComparingTo("0.5");
    assertThat(saved.getMaxTokens()).isEqualTo(1500);
    assertThat(saved.getApiKeyCiphertext()).isEqualTo("enc:v1:ciphertext");
    assertThat(saved.isActive()).isTrue();
    verify(markerRepository, times(1)).save(any());
  }

  @Test
  void treatsTheBundledOpenAiPlaceholderKeyAsNoKeyAtAll() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("spring.ai.model.chat", "openai")
            .withProperty(
                "spring.ai.openai.chat.base-url", "https://modellserver.example.internal/v1")
            .withProperty("spring.ai.openai.chat.model", "gpt-4o")
            .withProperty(
                "spring.ai.openai.chat.api-key", LlmModelSeeder.OPENAI_API_KEY_PLACEHOLDER);

    seederWith(environment).seedIfNeeded();

    verify(settingsEncryptor, never()).encrypt(any());
    ArgumentCaptor<LlmModel> captor = ArgumentCaptor.forClass(LlmModel.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getApiKeyCiphertext()).isNull();
  }

  @Test
  void seedsNoModelButStillWritesTheMarkerWhenNeitherOllamaNorOpenAiIsConfigured() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    MockEnvironment environment =
        new MockEnvironment().withProperty("spring.ai.model.chat", "something-else");

    seederWith(environment).seedIfNeeded();

    verify(repository, never()).save(any());
    verify(markerRepository, times(1)).save(any());
  }

  @Test
  void ensureV1SuffixAppendsExactlyOnce() {
    assertThat(LlmModelSeeder.ensureV1Suffix("http://ollama:11434"))
        .isEqualTo("http://ollama:11434/v1");
    assertThat(LlmModelSeeder.ensureV1Suffix("http://ollama:11434/v1"))
        .isEqualTo("http://ollama:11434/v1");
    assertThat(LlmModelSeeder.ensureV1Suffix("http://ollama:11434/v1/"))
        .isEqualTo("http://ollama:11434/v1");
    assertThat(LlmModelSeeder.ensureV1Suffix("http://ollama:11434/"))
        .isEqualTo("http://ollama:11434/v1");
  }
}
