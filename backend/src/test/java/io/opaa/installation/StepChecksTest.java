package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.account.PasswordPolicy;
import io.opaa.api.types.MailEncryption;
import io.opaa.auth.OidcAddressPolicy;
import io.opaa.auth.OidcProviderConnectionTester;
import io.opaa.llm.EmbeddingEndpoint;
import io.opaa.llm.EmbeddingModelService;
import io.opaa.llm.LlmModelConnectionTester;
import io.opaa.llm.ModelAddressPolicy;
import io.opaa.llm.ModelRoleAdministration;
import io.opaa.mail.MailConnectionTester;
import io.opaa.mail.MailSettingsSnapshot;
import io.opaa.security.PasswordEncoderConfiguration;
import io.opaa.security.SettingsEncryptionProperties;
import io.opaa.security.SettingsEncryptor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
  private final ModelAddressPolicy strict = ModelAddressPolicy.allowing(List.of());
  private final StepRules rules =
      new StepRules(
          new PasswordPolicy(() -> 12),
          new PasswordEncoderConfiguration().passwordEncoder(),
          encryptor,
          mock(OidcAddressPolicy.class),
          json,
          strict,
          InstallationTestMailTargets.LOOPBACK);
  private final LlmModelConnectionTester chatTester = mock(LlmModelConnectionTester.class);
  private final EmbeddingModelService embeddingModels = mock(EmbeddingModelService.class);
  private final MailConnectionTester mailTester = mock(MailConnectionTester.class);
  private final ModelRoleAdministration modelRoles = mock(ModelRoleAdministration.class);
  private final StepChecks checks =
      new StepChecks(
          rules,
          mock(OidcProviderConnectionTester.class),
          chatTester,
          encryptor,
          strict,
          embeddingModels,
          mailTester,
          modelRoles);

  // regression guard: no probe of the installation reaches a blocked address
  @Test
  void aBlockedModelAddressFailsTheCheckWithoutAnyRequest() {
    String payload =
        json.writeValueAsString(
            new StepRules.ChatModelPayload("http://169.254.169.254/v1", "qwen", null, null));

    StepChecks.Outcome outcome = checks.check(InstallationStep.CHAT_MODEL, payload, null, null);

    assertThat(outcome.succeeded()).isFalse();
    assertThat(outcome.message())
        .contains("OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST")
        .doesNotContain("OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST");
    verifyNoInteractions(chatTester);
  }

  @Test
  void aBlockedEmbeddingAddressFailsTheCheckWithoutAProbe() {
    String payload =
        json.writeValueAsString(
            new StepRules.EmbeddingModelPayload("http://169.254.169.254/v1", "bge-m3", null, null));

    StepChecks.Outcome outcome =
        checks.check(InstallationStep.EMBEDDING_MODEL, payload, null, null);

    assertThat(outcome.succeeded()).isFalse();
    assertThat(outcome.dimensions()).isNull();
    verifyNoInteractions(embeddingModels);
  }

  // regression guard: the mail check reaches no address the installation refuses
  @Test
  void aRefusedMailServerFailsTheCheckWithoutAnyConnection() {
    String payload =
        json.writeValueAsString(
            new StepRules.MailPayload(
                "169.254.169.254", 25, MailEncryption.NONE, null, "opaa@stadt.example", null));

    StepChecks.Outcome outcome = checks.check(InstallationStep.MAIL, payload, null, "a@b.example");

    assertThat(outcome.succeeded()).isFalse();
    assertThat(outcome.message()).contains("OPAA_INSTALLATION_MAIL_TARGET_ALLOWLIST");
    verifyNoInteractions(mailTester);
  }

  @Test
  void theMailCheckConnectsWithTheDecryptedPasswordAndHandsOnTheTestRecipient() {
    String payload =
        json.writeValueAsString(
            new StepRules.MailPayload(
                "127.0.0.1", 2525, MailEncryption.NONE, "kennung", "opaa@stadt.example", "OPAA"));
    when(mailTester.test(any(MailSettingsSnapshot.class), eq("it@stadt.example")))
        .thenReturn(new MailConnectionTester.Outcome(true, "gelungen"));

    StepChecks.Outcome outcome =
        checks.check(
            InstallationStep.MAIL, payload, encryptor.encrypt("geheim"), "it@stadt.example");

    assertThat(outcome.succeeded()).isTrue();
    ArgumentCaptor<MailSettingsSnapshot> settings =
        ArgumentCaptor.forClass(MailSettingsSnapshot.class);
    verify(mailTester).test(settings.capture(), eq("it@stadt.example"));
    assertThat(settings.getValue().password()).isEqualTo("geheim");
    assertThat(settings.getValue().port()).isEqualTo(2525);
    assertThat(settings.getValue().encryption()).isEqualTo(MailEncryption.NONE);
  }

  @Test
  void theProbeEmbeddingOfAnAllowedAddressReportsItsVectorSize() {
    StepChecks permissive =
        new StepChecks(
            rules,
            mock(OidcProviderConnectionTester.class),
            chatTester,
            encryptor,
            ModelAddressPolicy.disabled(),
            embeddingModels,
            mailTester,
            modelRoles);
    when(embeddingModels.probe(any(EmbeddingEndpoint.class)))
        .thenReturn(
            new EmbeddingModelService.ProbeOutcome(true, "Probeeinbettung erfolgreich", 768));
    String payload =
        json.writeValueAsString(
            new StepRules.EmbeddingModelPayload("http://ollama:11434/v1", "bge-m3", null, null));

    StepChecks.Outcome outcome =
        permissive.check(InstallationStep.EMBEDDING_MODEL, payload, null, null);

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.dimensions()).isEqualTo(768);
  }

  // regression guard: the rerank check reaches no address the installation refuses
  @Test
  void aBlockedRerankAddressFailsTheCheckWithoutAProbe() {
    String payload =
        json.writeValueAsString(
            new StepRules.RerankModelPayload("http://169.254.169.254", "bge-reranker"));

    StepChecks.Outcome outcome = checks.check(InstallationStep.RERANK_MODEL, payload, null, null);

    assertThat(outcome.succeeded()).isFalse();
    assertThat(outcome.message()).contains("OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST");
    verifyNoInteractions(modelRoles);
  }

  @Test
  void theRerankCheckIsTheProbeOfTheAdministrationWithTheDecryptedKey() {
    StepChecks permissive =
        new StepChecks(
            rules,
            mock(OidcProviderConnectionTester.class),
            chatTester,
            encryptor,
            ModelAddressPolicy.disabled(),
            embeddingModels,
            mailTester,
            modelRoles);
    when(modelRoles.probeRerank("http://reranker:80", "bge-reranker", "geheim"))
        .thenReturn(new ModelRoleAdministration.TestOutcome(false, "HTTP 401"));
    String payload =
        json.writeValueAsString(
            new StepRules.RerankModelPayload("http://reranker:80", "bge-reranker"));

    StepChecks.Outcome outcome =
        permissive.check(InstallationStep.RERANK_MODEL, payload, encryptor.encrypt("geheim"), null);

    assertThat(outcome.succeeded()).isFalse();
    assertThat(outcome.message()).isEqualTo("HTTP 401");
    assertThat(outcome.dimensions()).isNull();
  }

  @Test
  void anUnreadableRerankKeyFailsTheCheckWithoutAProbe() {
    StepChecks permissive =
        new StepChecks(
            rules,
            mock(OidcProviderConnectionTester.class),
            chatTester,
            encryptor,
            ModelAddressPolicy.disabled(),
            embeddingModels,
            mailTester,
            modelRoles);
    String payload =
        json.writeValueAsString(
            new StepRules.RerankModelPayload("http://reranker:80", "bge-reranker"));

    StepChecks.Outcome outcome =
        permissive.check(InstallationStep.RERANK_MODEL, payload, "kein-chiffrat", null);

    assertThat(outcome.succeeded()).isFalse();
    assertThat(outcome.message()).contains("nicht lesbar");
    verifyNoInteractions(modelRoles);
  }

  @Test
  void theBrandingCheckAppliesTheRulesAgainWithoutAnyConnection() {
    String passing =
        json.writeValueAsString(
            new StepRules.BrandingPayload("Landesamt-Assistent", null, null, "#7A3E9D", null));
    String forged =
        json.writeValueAsString(new StepRules.BrandingPayload(null, null, null, "rot", null));

    assertThat(checks.check(InstallationStep.BRANDING, passing, null, null).succeeded()).isTrue();
    StepChecks.Outcome refused = checks.check(InstallationStep.BRANDING, forged, null, null);
    assertThat(refused.succeeded()).isFalse();
    assertThat(refused.message()).contains("Hex-Wert");
    verifyNoInteractions(chatTester, embeddingModels, mailTester);
  }

  @Test
  void aBlockedTranscriptionAddressFailsTheCheckWithoutAnyRequest() {
    String payload =
        json.writeValueAsString(
            new StepRules.TranscriptionModelPayload("http://169.254.169.254/v1", "whisper", "de"));

    StepChecks.Outcome outcome =
        checks.check(InstallationStep.TRANSCRIPTION_MODEL, payload, null, null);

    assertThat(outcome.succeeded()).isFalse();
    verifyNoInteractions(modelRoles);
  }

  @Test
  void theTranscriptionModelIsCheckedWithTheProbeOfTheAdministrationAndItsOwnKey() {
    StepChecks open =
        new StepChecks(
            rules,
            mock(OidcProviderConnectionTester.class),
            chatTester,
            encryptor,
            ModelAddressPolicy.disabled(),
            embeddingModels,
            mailTester,
            modelRoles);
    when(modelRoles.probeTranscription("http://speaches:8000/v1", "whisper", "sk-stt"))
        .thenReturn(
            new ModelRoleAdministration.TestOutcome(
                true, "Verbindung erfolgreich, der Endpunkt hat geantwortet."));
    String payload =
        json.writeValueAsString(
            new StepRules.TranscriptionModelPayload("http://speaches:8000/v1", "whisper", "de"));

    StepChecks.Outcome outcome =
        open.check(
            InstallationStep.TRANSCRIPTION_MODEL, payload, encryptor.encrypt("sk-stt"), null);

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.message())
        .isEqualTo("Verbindung erfolgreich, der Endpunkt hat geantwortet.");
    assertThat(outcome.dimensions()).isNull();
  }
}
