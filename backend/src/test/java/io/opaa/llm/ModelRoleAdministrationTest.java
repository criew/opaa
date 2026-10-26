package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.audit.AuditEventRecorder;
import io.opaa.common.FeatureFlags;
import io.opaa.common.FieldValidationException;
import io.opaa.security.SettingsEncryptor;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The limits of the operation around the model roles (ADR-0060, Entscheidung 2): speech input
 * switched off by the operation keeps the transcription role from being switched on, and a variable
 * of the former configuration is named on the role's page, never taken over.
 */
class ModelRoleAdministrationTest {

  private final RerankModelSettingsRepository rerankRows =
      mock(RerankModelSettingsRepository.class);
  private final TranscriptionModelSettingsRepository transcriptionRows =
      mock(TranscriptionModelSettingsRepository.class);
  private final AuditEventRecorder audit = mock(AuditEventRecorder.class);

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
        mock(RerankClient.class),
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
