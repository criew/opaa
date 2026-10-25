package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.account.PasswordPolicy;
import io.opaa.auth.OidcAddressPolicy;
import io.opaa.auth.OidcProviderConnectionTester;
import io.opaa.llm.EmbeddingEndpoint;
import io.opaa.llm.EmbeddingModelService;
import io.opaa.llm.LlmModelConnectionTester;
import io.opaa.security.PasswordEncoderConfiguration;
import io.opaa.security.SettingsEncryptionProperties;
import io.opaa.security.SettingsEncryptor;
import io.opaa.security.TargetAddressValidator;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The checks of the model steps send nothing to an address the target address check refuses: the
 * address is checked again right before the probe, since a name may resolve elsewhere by then. The
 * probe embedding of the embedding model step reports the vector size it found.
 */
class StepChecksTest {

  private final JsonMapper json = JsonMapper.builder().build();
  private final SettingsEncryptor encryptor =
      new SettingsEncryptor(
          new SettingsEncryptionProperties("c2V0dGluZ3NkZXZrZXkwMHNldHRpbmdzZGV2a2V5MDE="));
  private final TargetAddressValidator strict = new TargetAddressValidator(true, List.of());
  private final StepRules rules =
      new StepRules(
          new PasswordPolicy(() -> 12),
          new PasswordEncoderConfiguration().passwordEncoder(),
          encryptor,
          mock(OidcAddressPolicy.class),
          json,
          strict);
  private final LlmModelConnectionTester chatTester = mock(LlmModelConnectionTester.class);
  private final EmbeddingModelService embeddingModels = mock(EmbeddingModelService.class);
  private final StepChecks checks =
      new StepChecks(
          rules,
          mock(OidcProviderConnectionTester.class),
          chatTester,
          encryptor,
          strict,
          embeddingModels);

  // regression guard: no probe of the installation reaches a blocked address
  @Test
  void aBlockedModelAddressFailsTheCheckWithoutAnyRequest() {
    String payload =
        json.writeValueAsString(
            new StepRules.ChatModelPayload("http://169.254.169.254/v1", "qwen", null, null));

    StepChecks.Outcome outcome = checks.check(InstallationStep.CHAT_MODEL, payload, null);

    assertThat(outcome.succeeded()).isFalse();
    assertThat(outcome.message()).contains("OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST");
    verifyNoInteractions(chatTester);
  }

  @Test
  void aBlockedEmbeddingAddressFailsTheCheckWithoutAProbe() {
    String payload =
        json.writeValueAsString(
            new StepRules.EmbeddingModelPayload("http://169.254.169.254/v1", "bge-m3", null, null));

    StepChecks.Outcome outcome = checks.check(InstallationStep.EMBEDDING_MODEL, payload, null);

    assertThat(outcome.succeeded()).isFalse();
    assertThat(outcome.dimensions()).isNull();
    verifyNoInteractions(embeddingModels);
  }

  @Test
  void theProbeEmbeddingOfAnAllowedAddressReportsItsVectorSize() {
    StepChecks permissive =
        new StepChecks(
            rules,
            mock(OidcProviderConnectionTester.class),
            chatTester,
            encryptor,
            new TargetAddressValidator(false, List.of()),
            embeddingModels);
    when(embeddingModels.probe(any(EmbeddingEndpoint.class)))
        .thenReturn(
            new EmbeddingModelService.ProbeOutcome(true, "Probeeinbettung erfolgreich", 768));
    String payload =
        json.writeValueAsString(
            new StepRules.EmbeddingModelPayload("http://ollama:11434/v1", "bge-m3", null, null));

    StepChecks.Outcome outcome = permissive.check(InstallationStep.EMBEDDING_MODEL, payload, null);

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.dimensions()).isEqualTo(768);
  }
}
