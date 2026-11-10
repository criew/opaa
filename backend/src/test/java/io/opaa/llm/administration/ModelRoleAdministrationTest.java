package io.opaa.llm.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.RealtimeTranscriptionProtocol;
import io.opaa.api.types.TextRecognitionConnectionType;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.common.FeatureFlags;
import io.opaa.common.FieldValidationException;
import io.opaa.llm.ModelAddressPolicy;
import io.opaa.llm.OptionalRoleState;
import io.opaa.llm.OptionalRoleStatus;
import io.opaa.llm.rerank.RerankClient;
import io.opaa.llm.rerank.RerankEndpoint;
import io.opaa.llm.rerank.RerankEndpoints;
import io.opaa.llm.rerank.RerankModelRole;
import io.opaa.llm.rerank.RerankModelSettings;
import io.opaa.llm.rerank.RerankModelSettingsRepository;
import io.opaa.llm.rerank.RerankProperties;
import io.opaa.llm.textrecognition.TextRecognitionClient;
import io.opaa.llm.textrecognition.TextRecognitionEndpoint;
import io.opaa.llm.textrecognition.TextRecognitionEndpoints;
import io.opaa.llm.textrecognition.TextRecognitionModelRole;
import io.opaa.llm.textrecognition.TextRecognitionModelSettings;
import io.opaa.llm.textrecognition.TextRecognitionModelSettingsRepository;
import io.opaa.llm.textrecognition.TextRecognitionProperties;
import io.opaa.llm.transcription.RealtimeTranscriptionClient;
import io.opaa.llm.transcription.RealtimeTranscriptionEndpoint;
import io.opaa.llm.transcription.TranscriptionClient;
import io.opaa.llm.transcription.TranscriptionEndpoint;
import io.opaa.llm.transcription.TranscriptionEndpoints;
import io.opaa.llm.transcription.TranscriptionModelRole;
import io.opaa.llm.transcription.TranscriptionModelSettings;
import io.opaa.llm.transcription.TranscriptionModelSettingsRepository;
import io.opaa.llm.transcription.TranscriptionProperties;
import io.opaa.llm.transcription.TranscriptionVocabulary;
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
  private final TranscriptionClient transcriptionClient = mock(TranscriptionClient.class);
  private final RealtimeTranscriptionClient realtimeClient =
      mock(RealtimeTranscriptionClient.class);
  private final TextRecognitionModelSettingsRepository textRecognitionRows =
      mock(TextRecognitionModelSettingsRepository.class);
  private final TextRecognitionClient textRecognitionClient = mock(TextRecognitionClient.class);
  private TextRecognitionProperties textRecognitionProperties =
      TextRecognitionProperties.disabled();

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
                    "de",
                    null,
                    null))
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
            UUID.randomUUID(),
            UUID.randomUUID(),
            false,
            "http://8.8.8.8/v1",
            "whisper",
            null,
            "",
            null,
            null);

    assertThat(view.speechInputAvailable()).isFalse();
    assertThat(view.language()).isEmpty();
    assertThat(view.status().state()).isEqualTo(OptionalRoleState.DISABLED);
    verify(transcriptionRows).save(any());
  }

  @Test
  void theVocabularyIsStoredStrippedAndAudited() {
    ModelRoleAdministration administration = administration(true, new MockEnvironment());
    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);

    ModelRoleAdministration.TranscriptionModelView view =
        administration.changeTranscription(
            UUID.randomUUID(),
            UUID.randomUUID(),
            false,
            "http://8.8.8.8/v1",
            "whisper",
            null,
            "de",
            "  AO, BZSt ",
            null);

    assertThat(view.vocabulary()).isEqualTo("AO, BZSt");
    verify(audit).recordUserAction(event.capture());
    assertThat(event.getValue().after()).containsEntry("vocabulary", "AO, BZSt");
  }

  @Test
  void aVocabularyOverTheCapIsRefused() {
    ModelRoleAdministration administration = administration(true, new MockEnvironment());

    assertThatThrownBy(
            () ->
                administration.changeTranscription(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    false,
                    "http://8.8.8.8/v1",
                    "whisper",
                    null,
                    "de",
                    "x".repeat(TranscriptionVocabulary.MAX_LENGTH + 1),
                    null))
        .isInstanceOf(FieldValidationException.class)
        .satisfies(
            e ->
                assertThat(((FieldValidationException) e).fieldErrors())
                    .extracting(FieldValidationException.FieldError::field)
                    .containsExactly("transcription.vocabulary"));
    verify(transcriptionRows, never()).save(any());
  }

  @Test
  void theInstallationSwitchesTheRoleOnWithItsCiphertextUnderTheSystemProcess() {
    ModelRoleAdministration administration = administration(true, new MockEnvironment());
    ArgumentCaptor<TranscriptionModelSettings> row =
        ArgumentCaptor.forClass(TranscriptionModelSettings.class);
    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);

    ModelRoleAdministration.TranscriptionModelView view =
        administration.installTranscription(
            UUID.randomUUID(),
            "installation",
            " http://8.8.8.8/v1 ",
            " whisper ",
            "sk-stt",
            " EN ",
            null);

    verify(transcriptionRows).save(row.capture());
    assertThat(row.getValue().isEnabled()).isTrue();
    assertThat(row.getValue().getBaseUrl()).isEqualTo("http://8.8.8.8/v1");
    assertThat(row.getValue().getModelIdentifier()).isEqualTo("whisper");
    assertThat(row.getValue().getApiKeyCiphertext()).isEqualTo("enc:sk-stt");
    assertThat(row.getValue().getLanguage()).isEqualTo("en");
    assertThat(view.enabled()).isTrue();
    assertThat(view.apiKeySet()).isTrue();
    verify(audit).recordSystemProcessAction(event.capture());
    verify(audit, never()).recordUserAction(any());
    assertThat(event.getValue().actorRef()).isEqualTo("installation");
    assertThat(event.getValue().eventType()).isEqualTo(AuditEventType.TRANSCRIPTION_MODEL_CHANGED);
    assertThat(event.getValue().after())
        .containsEntry("enabled", true)
        .containsEntry("apiKeySet", true)
        .containsEntry("apiKeyChange", "SET")
        .containsEntry("language", "en")
        .doesNotContainValue("sk-stt")
        .doesNotContainValue("enc:sk-stt");
  }

  @Test
  void theInstallationKeepsTheRulesOfTheAdministration() {
    ModelRoleAdministration administration = administration(true, new MockEnvironment());

    assertThatThrownBy(
            () ->
                administration.installTranscription(
                    UUID.randomUUID(),
                    "installation",
                    "ftp://stt/v1",
                    "whisper",
                    null,
                    "deu",
                    null))
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e ->
                assertThat(e.fieldErrors())
                    .extracting(FieldValidationException.FieldError::field)
                    .containsExactly("transcription.baseUrl", "transcription.language"));
    verify(transcriptionRows, never()).save(any());
  }

  @Test
  void theProbeOfAnEndpointSendsExactlyTheGivenKey() {
    ModelRoleAdministration administration = administration(true, new MockEnvironment());
    ArgumentCaptor<TranscriptionEndpoint> endpoint =
        ArgumentCaptor.forClass(TranscriptionEndpoint.class);
    when(transcriptionClient.probe(endpoint.capture(), any())).thenReturn(null);

    ModelRoleAdministration.TestOutcome outcome =
        administration.probeTranscription("http://8.8.8.8/v1", "whisper", "sk-stt");

    assertThat(outcome.success()).isTrue();
    assertThat(outcome.message())
        .isEqualTo("Verbindung erfolgreich, der Endpunkt hat geantwortet.");
    assertThat(endpoint.getValue().apiKey()).isEqualTo("sk-stt");
    assertThat(endpoint.getValue().model()).isEqualTo("whisper");
  }

  @Test
  void aFailedProbeIsShownInGerman() {
    ModelRoleAdministration administration = administration(true, new MockEnvironment());
    when(transcriptionClient.probe(any(), any()))
        .thenReturn(new TranscriptionClient.ProbeFailure("connection refused", false));

    ModelRoleAdministration.TestOutcome outcome =
        administration.probeTranscription("http://8.8.8.8/v1", "whisper", null);

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.message()).isEqualTo("Die Verbindung wurde vom Server abgelehnt.");
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
            UUID.randomUUID(), "installation", " http://reranker:80 ", " bge ", "sk-rerank");

    assertThat(view.enabled()).isTrue();
    assertThat(view.baseUrl()).isEqualTo("http://reranker:80");
    assertThat(view.model()).isEqualTo("bge");
    assertThat(view.apiKeySet()).isTrue();
    ArgumentCaptor<RerankModelSettings> row = ArgumentCaptor.forClass(RerankModelSettings.class);
    verify(rerankRows).save(row.capture());
    assertThat(row.getValue().getApiKeyCiphertext()).isEqualTo("enc:sk-rerank");
    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).recordSystemProcessAction(event.capture());
    assertThat(event.getValue().actorRef()).isEqualTo("installation");
    assertThat(event.getValue().eventType()).isEqualTo(AuditEventType.RERANK_MODEL_CHANGED);
    assertThat(event.getValue().after())
        .containsEntry("enabled", true)
        .containsEntry("apiKeySet", true)
        .doesNotContainValue("sk-rerank")
        .doesNotContainValue("enc:sk-rerank");
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
        "de",
        null,
        null);

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

  @Test
  void theTextRecognitionRoleCannotBeSwitchedOnWhileTheOperationSwitchedItOff() {
    ModelRoleAdministration administration = administration(true, new MockEnvironment());

    assertThatThrownBy(
            () ->
                administration.changeTextRecognition(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    true,
                    TextRecognitionConnectionType.DOCLING_SERVE,
                    "http://8.8.8.8:5001",
                    null,
                    "geheim"))
        .isInstanceOf(FieldValidationException.class)
        .satisfies(
            e ->
                assertThat(((FieldValidationException) e).fieldErrors())
                    .extracting(FieldValidationException.FieldError::code)
                    .containsExactly("BLOCKED"));
    verify(textRecognitionRows, never()).save(any());
    verify(audit, never()).recordUserAction(any());
  }

  @Test
  void whileTheOperationSwitchedItOffTheTestContactsNoService() {
    ModelRoleAdministration administration = administration(true, new MockEnvironment());

    assertThatThrownBy(
            () ->
                administration.testTextRecognition(
                    TextRecognitionConnectionType.DOCLING_SERVE, "http://8.8.8.8:5001", null, "k"))
        .isInstanceOf(FieldValidationException.class);
    verify(textRecognitionClient, never()).test(any(), any());
    verify(textRecognitionClient, never()).probe(any(), any());
  }

  @Test
  void aTextRecognitionEndpointMayStillBePreparedWhileTheOperationSwitchedItOff() {
    ModelRoleAdministration administration = administration(true, new MockEnvironment());
    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);

    ModelRoleAdministration.TextRecognitionModelView view =
        administration.changeTextRecognition(
            UUID.randomUUID(),
            UUID.randomUUID(),
            false,
            TextRecognitionConnectionType.DOCLING_SERVE,
            "http://8.8.8.8:5001",
            "ignored-for-docling",
            "geheim");

    assertThat(view.textRecognitionAvailable()).isFalse();
    assertThat(view.model()).isNull();
    assertThat(view.apiKeySet()).isTrue();
    assertThat(view.status().state()).isEqualTo(OptionalRoleState.DISABLED);
    verify(audit).recordUserAction(event.capture());
    assertThat(event.getValue().eventType())
        .isEqualTo(AuditEventType.TEXT_RECOGNITION_MODEL_CHANGED);
    assertThat(event.getValue().after())
        .containsEntry("connectionType", "DOCLING_SERVE")
        .containsEntry("apiKeySet", true)
        .doesNotContainKey("model")
        .doesNotContainValue("geheim");
  }

  @Test
  void aVisionModelNeedsAModelWhileADoclingServeNeedsNone() {
    textRecognitionProperties = new TextRecognitionProperties(true, 0, null, 0, 0, null, null);
    ModelRoleAdministration administration = administration(true, new MockEnvironment());

    assertThatThrownBy(
            () ->
                administration.changeTextRecognition(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    true,
                    TextRecognitionConnectionType.OPENAI_VISION,
                    "http://8.8.8.8/v1",
                    " ",
                    null))
        .isInstanceOf(FieldValidationException.class)
        .satisfies(
            e ->
                assertThat(((FieldValidationException) e).fieldErrors())
                    .extracting(FieldValidationException.FieldError::field)
                    .containsExactly("textRecognition.model"));

    ModelRoleAdministration.TextRecognitionModelView view =
        administration.changeTextRecognition(
            UUID.randomUUID(),
            UUID.randomUUID(),
            true,
            TextRecognitionConnectionType.DOCLING_SERVE,
            "http://8.8.8.8:5001",
            null,
            null);
    assertThat(view.enabled()).isTrue();
    assertThat(view.textRecognitionAvailable()).isTrue();
  }

  @Test
  void theInstallationSwitchesTextRecognitionOnWithItsCiphertextUnderTheSystemProcess() {
    textRecognitionProperties = new TextRecognitionProperties(true, 0, null, 0, 0, null, null);
    ModelRoleAdministration administration = administration(true, new MockEnvironment());
    ArgumentCaptor<TextRecognitionModelSettings> row =
        ArgumentCaptor.forClass(TextRecognitionModelSettings.class);
    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);

    ModelRoleAdministration.TextRecognitionModelView view =
        administration.installTextRecognition(
            UUID.randomUUID(),
            "installation",
            TextRecognitionConnectionType.DOCLING_SERVE,
            " http://8.8.8.8:5001 ",
            "ignored-for-docling",
            "ocr-key");

    verify(textRecognitionRows).save(row.capture());
    assertThat(row.getValue().isEnabled()).isTrue();
    assertThat(row.getValue().getConnectionType())
        .isEqualTo(TextRecognitionConnectionType.DOCLING_SERVE);
    assertThat(row.getValue().getBaseUrl()).isEqualTo("http://8.8.8.8:5001");
    assertThat(row.getValue().getModelIdentifier()).isNull();
    assertThat(row.getValue().getApiKeyCiphertext()).isEqualTo("enc:ocr-key");
    assertThat(view.enabled()).isTrue();
    verify(audit).recordSystemProcessAction(event.capture());
    verify(audit, never()).recordUserAction(any());
    assertThat(event.getValue().actorRef()).isEqualTo("installation");
    assertThat(event.getValue().eventType())
        .isEqualTo(AuditEventType.TEXT_RECOGNITION_MODEL_CHANGED);
    assertThat(event.getValue().after())
        .containsEntry("enabled", true)
        .containsEntry("apiKeyChange", "SET")
        .doesNotContainValue("ocr-key")
        .doesNotContainValue("enc:ocr-key");
  }

  @Test
  void theInstallationCannotSwitchOnTextRecognitionWhileTheOperationSwitchedItOff() {
    ModelRoleAdministration administration = administration(true, new MockEnvironment());

    assertThatThrownBy(
            () ->
                administration.installTextRecognition(
                    UUID.randomUUID(),
                    "installation",
                    TextRecognitionConnectionType.DOCLING_SERVE,
                    "http://8.8.8.8:5001",
                    null,
                    null))
        .isInstanceOf(FieldValidationException.class);
    assertThatThrownBy(
            () ->
                administration.probeTextRecognition(
                    TextRecognitionConnectionType.DOCLING_SERVE, "http://8.8.8.8:5001", null, "k"))
        .isInstanceOf(FieldValidationException.class);
    verify(textRecognitionRows, never()).save(any());
    verify(textRecognitionClient, never()).test(any(), any());
  }

  @Test
  void theProbeOfTheInstallationSendsExactlyTheGivenKeyAndNamesTheStage() {
    textRecognitionProperties = new TextRecognitionProperties(true, 0, null, 0, 0, null, null);
    ModelRoleAdministration administration = administration(true, new MockEnvironment());
    ArgumentCaptor<TextRecognitionEndpoint> endpoint =
        ArgumentCaptor.forClass(TextRecognitionEndpoint.class);
    when(textRecognitionClient.test(endpoint.capture(), any()))
        .thenReturn(
            new TextRecognitionClient.TestResult(
                TextRecognitionClient.TestOutcome.KEY_REJECTED,
                "HTTP 401",
                false,
                TextRecognitionClient.FailedStage.AUTHENTICATION));

    ModelRoleAdministration.TextRecognitionTestOutcome outcome =
        administration.probeTextRecognition(
            TextRecognitionConnectionType.OPENAI_VISION, "http://8.8.8.8/v1", "paddle-vl", "k");

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.failedStage()).isEqualTo(TextRecognitionClient.FailedStage.AUTHENTICATION);
    assertThat(endpoint.getValue().apiKey()).isEqualTo("k");
    assertThat(endpoint.getValue().model()).isEqualTo("paddle-vl");
    assertThat(endpoint.getValue().connectionType())
        .isEqualTo(TextRecognitionConnectionType.OPENAI_VISION);
  }

  @Test
  void aTextRecognitionAddressWithCredentialsIsRefused() {
    textRecognitionProperties = new TextRecognitionProperties(true, 0, null, 0, 0, null, null);
    ModelRoleAdministration administration = administration(true, new MockEnvironment());

    assertThatThrownBy(
            () ->
                administration.changeTextRecognition(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    true,
                    TextRecognitionConnectionType.DOCLING_SERVE,
                    "http://user:secret@8.8.8.8:5001",
                    null,
                    null))
        .isInstanceOf(FieldValidationException.class)
        .hasMessageNotContaining("secret");
    verify(textRecognitionRows, never()).save(any());
  }

  @Test
  void eachTestOutcomeHasItsOwnMessage() {
    assertThat(
            ModelRoleAdministration.textRecognitionMessage(
                TextRecognitionConnectionType.DOCLING_SERVE,
                new TextRecognitionClient.TestResult(
                    TextRecognitionClient.TestOutcome.KEY_REJECTED,
                    "HTTP 401",
                    false,
                    TextRecognitionClient.FailedStage.AUTHENTICATION)))
        .contains("Zugangsschlüssel");
    assertThat(
            ModelRoleAdministration.textRecognitionMessage(
                TextRecognitionConnectionType.DOCLING_SERVE,
                new TextRecognitionClient.TestResult(
                    TextRecognitionClient.TestOutcome.ENGINE_MISSING,
                    "HTTP 422",
                    false,
                    TextRecognitionClient.FailedStage.RECOGNITION)))
        .contains("OCR-Engine");
    assertThat(
            ModelRoleAdministration.textRecognitionMessage(
                TextRecognitionConnectionType.OPENAI_VISION,
                new TextRecognitionClient.TestResult(
                    TextRecognitionClient.TestOutcome.ENGINE_MISSING,
                    "not listed",
                    false,
                    TextRecognitionClient.FailedStage.RECOGNITION)))
        .contains("Modell");
    assertThat(
            ModelRoleAdministration.textRecognitionMessage(
                TextRecognitionConnectionType.DOCLING_SERVE,
                new TextRecognitionClient.TestResult(
                    TextRecognitionClient.TestOutcome.NO_TEXT,
                    "no text",
                    false,
                    TextRecognitionClient.FailedStage.RECOGNITION)))
        .contains("keinen Text");
    assertThat(
            ModelRoleAdministration.textRecognitionMessage(
                TextRecognitionConnectionType.DOCLING_SERVE,
                new TextRecognitionClient.TestResult(
                    TextRecognitionClient.TestOutcome.UNREACHABLE,
                    "connection refused",
                    false,
                    TextRecognitionClient.FailedStage.ADDRESS)))
        .isEqualTo("Die Verbindung wurde vom Server abgelehnt.");
    assertThat(
            ModelRoleAdministration.textRecognitionMessage(
                TextRecognitionConnectionType.DOCLING_SERVE,
                new TextRecognitionClient.TestResult(
                    TextRecognitionClient.TestOutcome.UNREACHABLE,
                    "request timed out",
                    true,
                    TextRecognitionClient.FailedStage.RECOGNITION)))
        .contains("Zugangsschlüssel angenommen")
        .contains("nicht rechtzeitig");
  }

  @Test
  void aLiveUpstreamIsStoredWithItsOwnEncryptedKeyAndAudited() {
    ModelRoleAdministration administration = administration(true, new MockEnvironment());
    ArgumentCaptor<TranscriptionModelSettings> row =
        ArgumentCaptor.forClass(TranscriptionModelSettings.class);
    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);

    ModelRoleAdministration.TranscriptionModelView view =
        administration.changeTranscription(
            UUID.randomUUID(),
            UUID.randomUUID(),
            true,
            "https://api.openai.com/v1",
            "whisper-1",
            "sk-batch",
            "de",
            null,
            new ModelRoleAdministration.RealtimeChange(
                true,
                RealtimeTranscriptionProtocol.OPENAI,
                " wss://api.openai.com/v1/realtime?intent=transcription ",
                " gpt-live-transcribe ",
                "sk-live",
                2));

    verify(transcriptionRows).save(row.capture());
    assertThat(row.getValue().getRealtimeProtocol())
        .isEqualTo(RealtimeTranscriptionProtocol.OPENAI);
    assertThat(row.getValue().getRealtimeUrl())
        .isEqualTo("wss://api.openai.com/v1/realtime?intent=transcription");
    assertThat(row.getValue().getRealtimeModel()).isEqualTo("gpt-live-transcribe");
    assertThat(row.getValue().getRealtimeApiKeyCiphertext()).isEqualTo("enc:sk-live");
    assertThat(row.getValue().getRealtimeMaxSessions()).isEqualTo(2);
    assertThat(view.realtime().enabled()).isTrue();
    assertThat(view.realtime().apiKeySet()).isTrue();
    verify(audit).recordUserAction(event.capture());
    assertThat(event.getValue().after())
        .containsEntry("realtimeProtocol", "OPENAI")
        .containsEntry("realtimeUrl", "wss://api.openai.com/v1/realtime")
        .containsEntry("realtimeApiKeySet", true)
        .containsEntry("realtimeApiKeyChange", "SET")
        .doesNotContainValue("sk-live");
  }

  @Test
  void anOpenAiLiveUpstreamOnAnotherHostThanTheBatchNeedsAKeyOfItsOwn() {
    ModelRoleAdministration administration = administration(true, new MockEnvironment());

    assertThatThrownBy(
            () ->
                administration.changeTranscription(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    true,
                    "http://speaches:8000/v1",
                    "whisper",
                    "",
                    "de",
                    null,
                    new ModelRoleAdministration.RealtimeChange(
                        true,
                        RealtimeTranscriptionProtocol.OPENAI,
                        "wss://api.openai.com/v1/realtime?intent=transcription",
                        "gpt-live-transcribe",
                        null,
                        null)))
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e ->
                assertThat(e.fieldErrors())
                    .extracting(FieldValidationException.FieldError::field)
                    .containsExactly("transcription.realtime.apiKey"));
    verify(transcriptionRows, never()).save(any());

    administration.changeTranscription(
        UUID.randomUUID(),
        UUID.randomUUID(),
        true,
        "http://speaches:8000/v1",
        "whisper",
        "",
        "de",
        null,
        new ModelRoleAdministration.RealtimeChange(
            true,
            RealtimeTranscriptionProtocol.VLLM,
            "ws://speaches:8000/v1/realtime",
            "voxtral",
            null,
            null));
    verify(transcriptionRows).save(any());
  }

  @Test
  void aLiveAddressMustBeAWebSocketAddressWithProtocolAndModel() {
    ModelRoleAdministration administration = administration(true, new MockEnvironment());

    assertThatThrownBy(
            () ->
                administration.changeTranscription(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    false,
                    "",
                    "",
                    null,
                    "de",
                    null,
                    new ModelRoleAdministration.RealtimeChange(
                        true, null, "https://api.mistral.ai/v1", "", null, 0)))
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e ->
                assertThat(e.fieldErrors())
                    .extracting(FieldValidationException.FieldError::field)
                    .containsExactly(
                        "transcription.realtime.protocol",
                        "transcription.realtime.url",
                        "transcription.realtime.model",
                        "transcription.realtime.maxSessions"));
    verify(transcriptionRows, never()).save(any());
  }

  @Test
  void switchingLiveOffRemovesTheLiveUpstreamWithItsKey() {
    ModelRoleAdministration administration = administration(true, new MockEnvironment());
    TranscriptionModelSettings stored = new TranscriptionModelSettings(java.time.Instant.EPOCH);
    stored.replaceRealtime(
        RealtimeTranscriptionProtocol.VLLM, "ws://vllm:8000/v1/realtime", "voxtral", "enc:k", 1);
    when(transcriptionRows.findStored()).thenReturn(Optional.of(stored));

    ModelRoleAdministration.TranscriptionModelView view =
        administration.changeTranscription(
            UUID.randomUUID(),
            UUID.randomUUID(),
            false,
            "",
            "",
            null,
            null,
            null,
            new ModelRoleAdministration.RealtimeChange(false, null, null, null, null, null));

    assertThat(stored.getRealtimeProtocol()).isNull();
    assertThat(stored.getRealtimeUrl()).isNull();
    assertThat(stored.getRealtimeApiKeyCiphertext()).isNull();
    assertThat(view.realtime().enabled()).isFalse();
  }

  @Test
  void aChangedLiveAddressNeedsTheLiveKeyAgain() {
    ModelRoleAdministration administration = administration(true, new MockEnvironment());
    TranscriptionModelSettings stored = new TranscriptionModelSettings(java.time.Instant.EPOCH);
    stored.replaceRealtime(
        RealtimeTranscriptionProtocol.MISTRAL,
        "wss://api.mistral.ai/v1/audio/transcriptions/realtime",
        "voxtral-mini-transcribe-realtime-3",
        "enc:k",
        1);
    when(transcriptionRows.findStored()).thenReturn(Optional.of(stored));

    assertThatThrownBy(
            () ->
                administration.changeTranscription(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    false,
                    "",
                    "",
                    null,
                    null,
                    null,
                    new ModelRoleAdministration.RealtimeChange(
                        true,
                        RealtimeTranscriptionProtocol.MISTRAL,
                        "wss://elsewhere.example/v1/realtime",
                        "voxtral-mini-transcribe-realtime-3",
                        null,
                        null)))
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e ->
                assertThat(e.fieldErrors())
                    .extracting(FieldValidationException.FieldError::field)
                    .containsExactly("transcription.realtime.apiKey"));
  }

  @Test
  void theLiveTestSendsTheBatchKeyWhileNoLiveKeyOfItsOwnIsStored() {
    ModelRoleAdministration administration = administration(true, new MockEnvironment());
    TranscriptionModelSettings stored = new TranscriptionModelSettings(java.time.Instant.EPOCH);
    stored.replaceEndpoint(true, "https://api.openai.com/v1", "whisper-1", "enc:sk-batch", null);
    when(transcriptionRows.findStored()).thenReturn(Optional.of(stored));
    ArgumentCaptor<RealtimeTranscriptionEndpoint> probed =
        ArgumentCaptor.forClass(RealtimeTranscriptionEndpoint.class);
    when(realtimeClient.probe(probed.capture(), any())).thenReturn(null);

    ModelRoleAdministration.TestOutcome outcome =
        administration.testTranscriptionRealtime(
            RealtimeTranscriptionProtocol.OPENAI,
            "wss://api.openai.com/v1/realtime?intent=transcription",
            "gpt-live-transcribe",
            null);

    assertThat(outcome.success()).isTrue();
    assertThat(probed.getValue().apiKey()).isEqualTo("sk-batch");
    assertThat(probed.getValue().protocol()).isEqualTo(RealtimeTranscriptionProtocol.OPENAI);
  }

  @Test
  void theBatchKeyNeverGoesToALiveAddressOfAnotherOrigin() {
    ModelRoleAdministration administration = administration(true, new MockEnvironment());
    TranscriptionModelSettings stored = new TranscriptionModelSettings(java.time.Instant.EPOCH);
    stored.replaceEndpoint(true, "https://api.openai.com/v1", "whisper-1", "enc:sk-batch", null);
    when(transcriptionRows.findStored()).thenReturn(Optional.of(stored));
    ArgumentCaptor<RealtimeTranscriptionEndpoint> probed =
        ArgumentCaptor.forClass(RealtimeTranscriptionEndpoint.class);
    when(realtimeClient.probe(probed.capture(), any())).thenReturn(null);

    administration.testTranscriptionRealtime(
        RealtimeTranscriptionProtocol.MISTRAL,
        "wss://api.mistral.ai/v1/audio/transcriptions/realtime",
        "voxtral-mini-transcribe-realtime-3",
        null);

    assertThat(probed.getValue().apiKey()).isEmpty();
  }

  private ModelRoleAdministration administration(boolean speechInput, MockEnvironment environment) {
    when(rerankRows.findStored()).thenReturn(Optional.empty());
    when(transcriptionRows.findStored()).thenReturn(Optional.empty());
    when(textRecognitionRows.findStored()).thenReturn(Optional.empty());
    FeatureFlags features =
        new FeatureFlags(
            new MockEnvironment()
                .withProperty("opaa.features.speech-input.enabled", "" + speechInput));
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
        textRecognitionRows,
        mock(RerankEndpoints.class),
        mock(TranscriptionEndpoints.class),
        mock(TextRecognitionEndpoints.class),
        rerankRole,
        transcriptionRole,
        endpoint -> OptionalRoleStatus.disabled(),
        new TextRecognitionModelRole(
            textRecognitionProperties,
            () -> TextRecognitionEndpoint.FACTORY_DEFAULT,
            textRecognitionClient),
        features,
        rerankClient,
        transcriptionClient,
        realtimeClient,
        textRecognitionClient,
        prefixingEncryptor(),
        ModelAddressPolicy.disabled(),
        audit,
        environment,
        mock(ApplicationEventPublisher.class),
        mock(PlatformTransactionManager.class),
        mock(JdbcTemplate.class),
        Clock.systemUTC());
  }

  /** Stands in for the key: a stored ciphertext is {@code enc:} before the key it encrypts. */
  private static SettingsEncryptor prefixingEncryptor() {
    SettingsEncryptor encryptor = mock(SettingsEncryptor.class);
    when(encryptor.encrypt(anyString(), any()))
        .thenAnswer(call -> "enc:" + call.getArgument(0, String.class));
    when(encryptor.decrypt(anyString(), any()))
        .thenAnswer(call -> call.getArgument(0, String.class).substring("enc:".length()));
    return encryptor;
  }
}
