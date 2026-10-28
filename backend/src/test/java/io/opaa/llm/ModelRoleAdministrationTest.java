package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditEventType;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.common.FeatureFlags;
import io.opaa.common.FieldValidationException;
import io.opaa.security.SettingsEncryptor;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The limits of the operation around the model roles (ADR-0060, Entscheidung 2): speech input
 * switched off by the operation keeps the transcription role from being switched on, and a variable
 * of the former configuration is named on the role's page, never taken over. The installation
 * switches the rerank role on through the same write path, recorded under its system process, and
 * checks it with the same probe.
 */
class ModelRoleAdministrationTest {

  private final RerankModelSettingsRepository rerankRows =
      mock(RerankModelSettingsRepository.class);
  private final TranscriptionModelSettingsRepository transcriptionRows =
      mock(TranscriptionModelSettingsRepository.class);
  private final AuditEventRecorder audit = mock(AuditEventRecorder.class);
  private final RerankClient rerankClient = mock(RerankClient.class);

  @Test
  void theTranscriptionRoleCannotBeSwitchedOnWhileTheOperationSwitchedOffSpeechInput() {
    ModelRoleAdministration administration = administration(false, new MockEnvironment());

    assertThatThrownBy(
            () ->
                administration.changeTranscription(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    true,
                    "http://8.8.8.8/v1",
                    "whisper",
                    null,
                    "de"))
        .isInstanceOf(FieldValidationException.class)
        .satisfies(
            e ->
                assertThat(((FieldValidationException) e).fieldErrors())
                    .extracting(FieldValidationException.FieldError::field)
                    .containsExactly("transcription.enabled"));
    verify(transcriptionRows, never()).save(any());
    verify(audit, never()).recordUserAction(any());
  }

  @Test
  void anEndpointMayStillBePreparedWhileSpeechInputIsOff() {
    ModelRoleAdministration administration = administration(false, new MockEnvironment());

    ModelRoleAdministration.TranscriptionModelView view =
        administration.changeTranscription(
            UUID.randomUUID(), UUID.randomUUID(), false, "http://8.8.8.8/v1", "whisper", null, "");

    assertThat(view.speechInputAvailable()).isFalse();
    assertThat(view.language()).isEmpty();
    assertThat(view.status().state()).isEqualTo(TranscriptionRoleState.DISABLED);
    verify(transcriptionRows).save(any());
  }

  @Test
  void aStillSetVariableOfTheFormerConfigurationIsNamedButNotTakenOver() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("OPAA_RERANK_ENABLED", "true")
            .withProperty("opaa.rerank.base-url", "http://reranker:80")
            .withProperty("OPAA_TRANSCRIPTION_LANGUAGE", "en");
    ModelRoleAdministration administration = administration(true, environment);

    ModelRoleAdministration.RerankModelView rerank = administration.rerank();
    ModelRoleAdministration.TranscriptionModelView transcription = administration.transcription();

    assertThat(rerank.retiredVariables())
        .containsExactly("OPAA_RERANK_BASE_URL", "OPAA_RERANK_ENABLED");
    assertThat(rerank.enabled()).isFalse();
    assertThat(rerank.baseUrl()).isNull();
    assertThat(transcription.retiredVariables()).containsExactly("OPAA_TRANSCRIPTION_LANGUAGE");
    assertThat(transcription.language()).isEqualTo("de");
  }

  @Test
  void theInstallationSwitchesRerankingOnUnderItsSystemProcess() {
    ModelRoleAdministration administration = administration(false, new MockEnvironment());

    ModelRoleAdministration.RerankModelView view =
        administration.installRerank(
            UUID.randomUUID(), "installation", " http://reranker:80 ", " bge ", "v1:chiffrat");

    assertThat(view.enabled()).isTrue();
    assertThat(view.baseUrl()).isEqualTo("http://reranker:80");
    assertThat(view.model()).isEqualTo("bge");
    assertThat(view.apiKeySet()).isTrue();
    ArgumentCaptor<RerankModelSettings> row = ArgumentCaptor.forClass(RerankModelSettings.class);
    verify(rerankRows).save(row.capture());
    assertThat(row.getValue().getApiKeyCiphertext()).isEqualTo("v1:chiffrat");
    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).recordSystemProcessAction(event.capture());
    assertThat(event.getValue().actorRef()).isEqualTo("installation");
    assertThat(event.getValue().eventType()).isEqualTo(AuditEventType.RERANK_MODEL_CHANGED);
    assertThat(event.getValue().after())
        .containsEntry("enabled", true)
        .containsEntry("apiKeySet", true)
        .doesNotContainValue("v1:chiffrat");
    verify(audit, never()).recordUserAction(any());
  }

  // regression guard: a token in the query of a model address never reaches the audit log
  @Test
  void theAuditNamesTheAddressWithoutQueryOrFragment() {
    ModelRoleAdministration administration = administration(false, new MockEnvironment());

    administration.installRerank(
        UUID.randomUUID(), "installation", "http://reranker:80/v1?token=geheim", "bge", null);
    administration.changeTranscription(
        UUID.randomUUID(),
        UUID.randomUUID(),
        false,
        "http://speaches:8000/v1#token=geheim",
        "whisper",
        null,
        "de");

    ArgumentCaptor<AuditEvent> rerank = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).recordSystemProcessAction(rerank.capture());
    assertThat(rerank.getValue().after()).containsEntry("baseUrl", "http://reranker:80/v1");
    ArgumentCaptor<AuditEvent> transcription = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).recordUserAction(transcription.capture());
    assertThat(transcription.getValue().after())
        .containsEntry("baseUrl", "http://speaches:8000/v1");
    assertThat(rerank.getValue().after().toString() + transcription.getValue().after())
        .doesNotContain("geheim");
  }

  @Test
  void theInstallationCannotSwitchOnARerankRoleWithoutModel() {
    ModelRoleAdministration administration = administration(false, new MockEnvironment());

    assertThatThrownBy(
            () ->
                administration.installRerank(
                    UUID.randomUUID(), "installation", "http://reranker:80", " ", null))
        .isInstanceOf(FieldValidationException.class);
    verify(rerankRows, never()).save(any());
  }

  @Test
  void theProbeOfTheInstallationIsTheProbeOfTheTestInGerman() {
    ModelRoleAdministration administration = administration(false, new MockEnvironment());
    when(rerankClient.probe(any(), any()))
        .thenReturn(null)
        .thenReturn(new RerankClient.ProbeFailure("HTTP 401: unauthorized", false));

    ModelRoleAdministration.TestOutcome passed =
        administration.probeRerank("http://reranker:80", "bge", "sk");
    ModelRoleAdministration.TestOutcome failed =
        administration.probeRerank("http://reranker:80", "bge", null);

    assertThat(passed.success()).isTrue();
    assertThat(failed.success()).isFalse();
    assertThat(failed.message()).contains("Zugangsschlüssel");
    ArgumentCaptor<RerankEndpoint> endpoint = ArgumentCaptor.forClass(RerankEndpoint.class);
    verify(rerankClient, times(2)).probe(endpoint.capture(), any(Duration.class));
    assertThat(endpoint.getAllValues().get(0).apiKey()).isEqualTo("sk");
    assertThat(endpoint.getAllValues().get(1).apiKey()).isEmpty();
  }

  @Test
  void aClientFailureIsShownInGerman() {
    assertThat(ModelRoleAdministration.german("HTTP 401: authentication failed", false))
        .contains("Zugangsschlüssel");
    assertThat(ModelRoleAdministration.german("connection refused", false))
        .isEqualTo("Die Verbindung wurde vom Server abgelehnt.");
    assertThat(ModelRoleAdministration.german("anything", true)).contains("Zeitlimit");
    assertThat(ModelRoleAdministration.german("HTTP 503 from the rerank endpoint", false))
        .isEqualTo("Der Server antwortete mit HTTP 503.");
  }

  private ModelRoleAdministration administration(boolean speechInput, MockEnvironment environment) {
    when(rerankRows.findStored()).thenReturn(Optional.empty());
    when(transcriptionRows.findStored()).thenReturn(Optional.empty());
    FeatureFlags features =
        new FeatureFlags(
            new MockEnvironment()
                .withProperty("opaa.features.speech-input.enabled", "" + speechInput));
    ModelRoleEndpoints endpoints = mock(ModelRoleEndpoints.class);
    TranscriptionModelRole transcriptionRole =
        new TranscriptionModelRole(
            new TranscriptionProperties(1024, Duration.ofSeconds(5), 1),
            () -> TranscriptionEndpoint.FACTORY_DEFAULT,
            mock(TranscriptionClient.class),
            features);
    RerankModelRole rerankRole =
        new RerankModelRole(
            new RerankProperties(Duration.ofSeconds(5), null),
            () -> RerankEndpoint.FACTORY_DEFAULT,
            mock(RerankClient.class),
            Clock.systemUTC());
    return new ModelRoleAdministration(
        rerankRows,
        transcriptionRows,
        endpoints,
        rerankRole,
        transcriptionRole,
        features,
        rerankClient,
        mock(TranscriptionClient.class),
        mock(SettingsEncryptor.class),
        ModelAddressPolicy.disabled(),
        audit,
        environment,
        mock(ApplicationEventPublisher.class),
        mock(PlatformTransactionManager.class),
        mock(JdbcTemplate.class),
        Clock.systemUTC());
  }
}
