package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.security.SettingsEncryptionProperties;
import io.opaa.security.SettingsEncryptor;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Unit tests for {@link LlmModelSeeder} (#756/#762,
 * docs/features/llm-integration.md#übergang-aus-der-heutigen-konfiguration) - repository mocks let
 * these run without a database, covering the branching logic (legacy {@code
 * OPAA_AI_CHAT_PROVIDER=ollama} vs. the regular {@code spring.ai.openai.chat.*} takeover), the
 * {@code /v1} suffix handling and the seed marker directly.
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
        new MockEnvironment().withProperty("OPAA_AI_CHAT_PROVIDER", "ollama");

    seederWith(environment).seedIfNeeded();

    verify(repository, never()).save(any());
    verify(markerRepository, never()).save(any());
  }

  @Test
  void seedsAnActiveModelWithoutAKeyFromTheLegacyOllamaEnvironmentVariables() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("OPAA_AI_CHAT_PROVIDER", "ollama")
            .withProperty("OPAA_OLLAMA_BASE_URL", "http://ollama:11434")
            .withProperty("OPAA_OLLAMA_CHAT_MODEL", "phi3:mini");

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
  void fallsBackToTheLocalhostAddressWhenTheLegacyBaseUrlWasNeverSet() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    // No "docker" profile active on this MockEnvironment - mirrors a bootRun/host deployment,
    // which never set the now-removed OPAA_OLLAMA_BASE_URL because it always relied on the
    // "local" profile's own default.
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("OPAA_AI_CHAT_PROVIDER", "ollama")
            .withProperty("OPAA_OLLAMA_CHAT_MODEL", "phi3:mini");

    seederWith(environment).seedIfNeeded();

    ArgumentCaptor<LlmModel> captor = ArgumentCaptor.forClass(LlmModel.class);
    verify(repository, times(1)).save(captor.capture());
    assertThat(captor.getValue().getBaseUrl()).isEqualTo("http://localhost:11434/v1");
  }

  @Test
  void fallsBackToTheOllamaServiceNameOnTheDockerProfileWhenTheLegacyBaseUrlWasNeverSet() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("OPAA_AI_CHAT_PROVIDER", "ollama")
            .withProperty("OPAA_OLLAMA_CHAT_MODEL", "phi3:mini");
    environment.setActiveProfiles("docker");

    seederWith(environment).seedIfNeeded();

    ArgumentCaptor<LlmModel> captor = ArgumentCaptor.forClass(LlmModel.class);
    verify(repository, times(1)).save(captor.capture());
    assertThat(captor.getValue().getBaseUrl()).isEqualTo("http://ollama:11434/v1");
  }

  @Test
  void fallsBackToTheApplicationDefaultChatModelWhenTheLegacyModelWasNeverSet() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("OPAA_AI_CHAT_PROVIDER", "ollama")
            .withProperty("OPAA_OLLAMA_BASE_URL", "http://ollama:11434");

    seederWith(environment).seedIfNeeded();

    ArgumentCaptor<LlmModel> captor = ArgumentCaptor.forClass(LlmModel.class);
    verify(repository, times(1)).save(captor.capture());
    assertThat(captor.getValue().getModelIdentifier())
        .isEqualTo(LlmModelSeeder.LEGACY_OLLAMA_CHAT_MODEL_DEFAULT);
  }

  @Test
  void doesNotDoubleAnAlreadyPresentV1Suffix() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("OPAA_AI_CHAT_PROVIDER", "ollama")
            .withProperty("OPAA_OLLAMA_BASE_URL", "http://ollama:11434/v1")
            .withProperty("OPAA_OLLAMA_CHAT_MODEL", "phi3:mini");

    seederWith(environment).seedIfNeeded();

    ArgumentCaptor<LlmModel> captor = ArgumentCaptor.forClass(LlmModel.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getBaseUrl()).isEqualTo("http://ollama:11434/v1");
  }

  @Test
  void fallsThroughToTheOpenAiConfigurationWhenTheLegacyProviderIsSetButNeitherOllamaVariableIs() {
    // PR #766 review, Befund 5: OPAA_AI_CHAT_PROVIDER=ollama left over on its own - both
    // OPAA_OLLAMA_* variables already removed by the operator - must not seed the wrong
    // (Ollama-shaped) defaults onto an installation that is actually configured via
    // spring.ai.openai.chat.*.
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("OPAA_AI_CHAT_PROVIDER", "ollama")
            .withProperty(
                "spring.ai.openai.chat.base-url", "https://modellserver.example.internal/v1")
            .withProperty("spring.ai.openai.chat.model", "gpt-4o");

    seederWith(environment).seedIfNeeded();

    ArgumentCaptor<LlmModel> captor = ArgumentCaptor.forClass(LlmModel.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getBaseUrl())
        .isEqualTo("https://modellserver.example.internal/v1");
    assertThat(captor.getValue().getModelIdentifier()).isEqualTo("gpt-4o");
  }

  @Test
  void seedsFromTheLegacyOllamaEnvironmentVariablesViaARealSystemEnvironmentPropertySource() {
    // PR #766 review, Befund 7b: MockEnvironment.withProperty is a flat, exact-match property map
    // and would pass even if production code accidentally relied on a lookup style real OS
    // environment variables do not support. A SystemEnvironmentPropertySource is what an
    // environment variable actually resolves through in production.
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    Map<String, Object> systemEnvironment =
        Map.of(
            "OPAA_AI_CHAT_PROVIDER", "ollama",
            "OPAA_OLLAMA_BASE_URL", "http://ollama:11434",
            "OPAA_OLLAMA_CHAT_MODEL", "phi3:mini");
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, systemEnvironment));

    new LlmModelSeeder(repository, markerRepository, settingsEncryptor, environment).seedIfNeeded();

    ArgumentCaptor<LlmModel> captor = ArgumentCaptor.forClass(LlmModel.class);
    verify(repository, times(1)).save(captor.capture());
    LlmModel saved = captor.getValue();
    assertThat(saved.getBaseUrl()).isEqualTo("http://ollama:11434/v1");
    assertThat(saved.getModelIdentifier()).isEqualTo("phi3:mini");
    assertThat(saved.getApiKeyCiphertext()).isNull();
    verify(markerRepository, times(1)).save(any());
  }

  @Test
  void seedsFromTheOpenAiConfigurationIncludingTheEncryptedApiKeyWhenNoLegacyProviderIsSet() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    when(settingsEncryptor.isKeyConfigured()).thenReturn(true);
    when(settingsEncryptor.encrypt("sk-configured-key")).thenReturn("enc:v1:ciphertext");
    MockEnvironment environment =
        new MockEnvironment()
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
  void ignoresALeftoverOpaaAiChatProviderSetToOpenaiExplicitly() {
    // An existing installation that already ran OPAA_AI_CHAT_PROVIDER=openai before #762 - the
    // variable is obsolete now, but leaving it set (harmlessly ignored) must not divert the
    // takeover onto the legacy Ollama path.
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("OPAA_AI_CHAT_PROVIDER", "openai")
            .withProperty(
                "spring.ai.openai.chat.base-url", "https://modellserver.example.internal/v1")
            .withProperty("spring.ai.openai.chat.model", "gpt-4o");

    seederWith(environment).seedIfNeeded();

    ArgumentCaptor<LlmModel> captor = ArgumentCaptor.forClass(LlmModel.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getBaseUrl())
        .isEqualTo("https://modellserver.example.internal/v1");
  }

  @Test
  void treatsTheBundledOpenAiPlaceholderKeyAsNoKeyAtAll() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    MockEnvironment environment =
        new MockEnvironment()
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
  void skipsTheTakeoverWithoutWritingAMarkerWhenTheApiKeyIsConfiguredButTheEncryptionKeyIsNot() {
    // #771: OPAA_SETTINGS_ENCRYPTION_KEY missing must not abort application startup - only skip
    // this one-time takeover, without writing LlmModelSeedMarker, so it is retried automatically
    // on the next start once the operator sets the key. Deliberately a *real* SettingsEncryptor
    // here (not the mock used by the other tests in this class) with no key configured - a mocked
    // encrypt() call would never exercise SettingsEncryptor#requireKey() and would not have
    // reproduced the original bug (the IllegalStateException propagating out of seedIfNeeded()
    // and aborting Application.run()).
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    SettingsEncryptor realEncryptorWithoutAKey =
        new SettingsEncryptor(new SettingsEncryptionProperties(null));
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(
                "spring.ai.openai.chat.base-url", "https://modellserver.example.internal/v1")
            .withProperty("spring.ai.openai.chat.model", "gpt-4o")
            .withProperty("spring.ai.openai.chat.api-key", "sk-configured-key");
    LlmModelSeeder seeder =
        new LlmModelSeeder(repository, markerRepository, realEncryptorWithoutAKey, environment);

    assertThatCode(seeder::seedIfNeeded).doesNotThrowAnyException();

    verify(repository, never()).save(any());
    verify(markerRepository, never()).save(any());
  }

  @Test
  void seedsNoModelButStillWritesTheMarkerWhenTheOpenAiConfigurationHasNoBaseUrl() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    MockEnvironment environment = new MockEnvironment();

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
