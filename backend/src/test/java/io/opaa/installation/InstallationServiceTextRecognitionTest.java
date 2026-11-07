package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.account.InitialAdministrator;
import io.opaa.api.types.TextRecognitionConnectionType;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.UserRepository;
import io.opaa.branding.BrandingSettingsService;
import io.opaa.common.FeatureFlags;
import io.opaa.common.ValidationException;
import io.opaa.directory.OidcProviderService;
import io.opaa.installation.InstallationState.CompletionSource;
import io.opaa.llm.EmbeddingModelService;
import io.opaa.llm.LlmModelService;
import io.opaa.llm.ModelRoleAdministration;
import io.opaa.mail.MailSettingsService;
import io.opaa.organization.Organization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The text recognition model step hangs on the operation's switch {@code opaa.ocr.enabled}
 * (ADR-0060, Entscheidung 18): with it on, the completion switches the role on through the model
 * administration; with it off, the step is neither listed, saved, skipped nor completed, and a step
 * saved while it was on holds nothing up and is left out.
 */
class InstallationServiceTextRecognitionTest {

  private static final Instant NOW = Instant.EPOCH;
  private static final String TEXT_RECOGNITION =
      "{\"connectionType\":\"DOCLING_SERVE\",\"baseUrl\":\"http://opaa-ocr:5001\"}";

  private final InstallationStateRepository states = mock(InstallationStateRepository.class);
  private final InstallationStepRepository steps = mock(InstallationStepRepository.class);
  private final StepRules rules = mock(StepRules.class);
  private final ModelRoleAdministration modelRoles = mock(ModelRoleAdministration.class);
  private final AuditEventRecorder audit = mock(AuditEventRecorder.class);
  private final StepRules real =
      new StepRules(null, null, null, null, JsonMapper.builder().build(), null, null);

  @BeforeEach
  void setUp() {
    when(states.findForUpdate()).thenReturn(Optional.of(InstallationState.pending(NOW)));
    when(rules.firstAdminPayload(any()))
        .thenAnswer(call -> real.firstAdminPayload(call.getArgument(0)));
    when(rules.signInPayload(any())).thenAnswer(call -> real.signInPayload(call.getArgument(0)));
    when(rules.textRecognitionModelPayload(any()))
        .thenAnswer(call -> real.textRecognitionModelPayload(call.getArgument(0)));
    when(rules.view(any(), any(), any()))
        .thenAnswer(
            call -> real.view(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
  }

  @Test
  void theCompletionSwitchesTheCheckedTextRecognitionModelOnThroughTheAdministration() {
    when(steps.findAll())
        .thenReturn(
            List.of(
                mandatoryAdmin(),
                mandatorySignIn(),
                record(
                    InstallationStep.TEXT_RECOGNITION_MODEL, TEXT_RECOGNITION, "chiffrat", true)));
    when(rules.apiKey(InstallationStep.TEXT_RECOGNITION_MODEL, "chiffrat")).thenReturn("ocr-key");

    service(true).complete(CompletionSource.INSTALLATION_API);

    verify(modelRoles)
        .installTextRecognition(
            Organization.DEFAULT_ID,
            InstallationService.SYSTEM_ACTOR,
            TextRecognitionConnectionType.DOCLING_SERVE,
            "http://opaa-ocr:5001",
            null,
            "ocr-key");
  }

  @Test
  void aSkippedTextRecognitionModelLeavesTextRecognitionOffAndIsNamedAsSkipped() {
    when(steps.findAll()).thenReturn(List.of(mandatoryAdmin(), mandatorySignIn()));
    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);

    InstallationService service = service(true);
    assertThat(service.overview().steps())
        .extracting(InstallationOverview.StepState::step)
        .containsSubsequence(
            InstallationStep.TRANSCRIPTION_MODEL,
            InstallationStep.TEXT_RECOGNITION_MODEL,
            InstallationStep.MAIL);
    service.complete(CompletionSource.INSTALLATION_API);

    verify(modelRoles, never())
        .installTextRecognition(any(), anyString(), any(), any(), any(), any());
    verify(audit, org.mockito.Mockito.atLeastOnce()).recordSystemProcessAction(events.capture());
    assertThat(events.getAllValues().getLast().after().get("skippedSteps").toString())
        .contains("TEXT_RECOGNITION_MODEL");
  }

  @Test
  void withoutTheOperationsReleaseTheStepIsNotListedAndASavedOneHoldsNothingUp() {
    when(steps.findAll())
        .thenReturn(
            List.of(
                mandatoryAdmin(),
                mandatorySignIn(),
                record(InstallationStep.TEXT_RECOGNITION_MODEL, TEXT_RECOGNITION, null, false)));
    InstallationService service = service(false);

    InstallationOverview overview = service.overview();

    assertThat(overview.steps())
        .extracting(InstallationOverview.StepState::step)
        .doesNotContain(InstallationStep.TEXT_RECOGNITION_MODEL)
        .contains(InstallationStep.TRANSCRIPTION_MODEL, InstallationStep.MAIL);
    assertThat(overview.completable()).isTrue();

    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    service.complete(CompletionSource.INSTALLATION_API);

    verify(modelRoles, never())
        .installTextRecognition(any(), anyString(), any(), any(), any(), any());
    verify(audit, org.mockito.Mockito.atLeastOnce()).recordSystemProcessAction(events.capture());
    assertThat(events.getAllValues().getLast().after().get("skippedSteps").toString())
        .doesNotContain("TEXT_RECOGNITION_MODEL");
  }

  @Test
  void theSavedStepIsAuditedWithItsConnectionTypeAndTheModelOnlyForAVisionModel() {
    when(rules.toStored(any(), any(), any()))
        .thenReturn(new StepRules.Stored(TEXT_RECOGNITION, null))
        .thenReturn(
            new StepRules.Stored(
                "{\"connectionType\":\"OPENAI_VISION\",\"baseUrl\":\"http://vllm:8000/v1\","
                    + "\"model\":\"paddle-vl\"}",
                null));
    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    InstallationService service = service(true);

    service.save(
        new StepInput.TextRecognitionModel(
            TextRecognitionConnectionType.DOCLING_SERVE, "http://opaa-ocr:5001", null, null));
    service.save(
        new StepInput.TextRecognitionModel(
            TextRecognitionConnectionType.OPENAI_VISION, "http://vllm:8000/v1", "paddle-vl", null));

    verify(audit, org.mockito.Mockito.times(2)).recordSystemProcessAction(events.capture());
    assertThat(events.getAllValues().getFirst().after())
        .containsEntry("connectionType", "DOCLING_SERVE")
        .containsEntry("baseUrl", "http://opaa-ocr:5001")
        .doesNotContainKey("model");
    assertThat(events.getAllValues().getLast().after())
        .containsEntry("connectionType", "OPENAI_VISION")
        .containsEntry("model", "paddle-vl");
  }

  @Test
  void withoutTheOperationsReleaseTheStepCannotBeSavedCheckedOrSkipped() {
    InstallationService service = service(false);

    assertThatThrownBy(
            () ->
                service.save(
                    new StepInput.TextRecognitionModel(
                        TextRecognitionConnectionType.DOCLING_SERVE,
                        "http://opaa-ocr:5001",
                        null,
                        null)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("OPAA_OCR_ENABLED");
    assertThatThrownBy(() -> service.check(InstallationStep.TEXT_RECOGNITION_MODEL))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> service.skip(InstallationStep.TEXT_RECOGNITION_MODEL))
        .isInstanceOf(ValidationException.class);
    verify(steps, never()).save(any());
    verify(rules, never()).toStored(any(), any(), any());
  }

  @Test
  void withoutTheOperationsReleaseAnAnswerIgnoresTheTextRecognitionModel() {
    StepChecks checks = mock(StepChecks.class);
    when(rules.toStored(any(), any(), any()))
        .thenAnswer(
            call ->
                switch (call.<StepInput>getArgument(0).step()) {
                  case FIRST_ADMIN ->
                      new StepRules.Stored(
                          "{\"email\":\"it@stadt.example\",\"displayName\":\"IT\"}", "hash");
                  case SIGN_IN -> new StepRules.Stored("{\"mode\":\"LOCAL_ONLY\"}", null);
                  default -> new StepRules.Stored(TEXT_RECOGNITION, null);
                });
    when(checks.check(any(), any(), any(), any())).thenReturn(new StepChecks.Outcome(true, "ok"));
    Map<InstallationStep, InstallationStepRecord> stored = new EnumMap<>(InstallationStep.class);
    when(steps.save(any()))
        .thenAnswer(
            call -> {
              InstallationStepRecord record = call.getArgument(0);
              stored.put(record.getStep(), record);
              return record;
            });
    when(steps.findAll()).thenAnswer(call -> List.copyOf(stored.values()));
    when(steps.findForUpdate(any()))
        .thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));

    InstallationOverview completed =
        service(false, checks)
            .applyAnswers(
                List.of(
                    new StepInput.FirstAdmin("it@stadt.example", "IT", "Passwort-2026-lang"),
                    new StepInput.SignIn(StepInput.SignInMode.LOCAL_ONLY, null),
                    new StepInput.TextRecognitionModel(
                        TextRecognitionConnectionType.DOCLING_SERVE,
                        "http://opaa-ocr:5001",
                        null,
                        "ocr-key")),
                CompletionSource.ANSWER_FILE_API);

    assertThat(completed.pending()).isFalse();
    verify(checks, never()).check(eq(InstallationStep.TEXT_RECOGNITION_MODEL), any(), any(), any());
    verify(rules, never()).toStored(any(StepInput.TextRecognitionModel.class), any(), any());
    verify(modelRoles, never())
        .installTextRecognition(any(), anyString(), any(), any(), any(), any());
  }

  private InstallationService service(boolean textRecognition) {
    return service(textRecognition, mock(StepChecks.class));
  }

  private InstallationService service(boolean textRecognition, StepChecks checks) {
    return new InstallationService(
        states,
        steps,
        rules,
        checks,
        mock(InitialAdministrator.class),
        mock(OidcProviderService.class),
        mock(LlmModelService.class),
        mock(EmbeddingModelService.class),
        modelRoles,
        mock(MailSettingsService.class),
        mock(BrandingSettingsService.class),
        new OfferedSteps(new FeatureFlags(new MockEnvironment()), textRecognition),
        audit,
        mock(UserRepository.class),
        mock(DatabaseInstallationStatus.class),
        Clock.fixed(NOW, ZoneOffset.UTC),
        mock(Environment.class),
        mock(PlatformTransactionManager.class));
  }

  private static InstallationStepRecord mandatoryAdmin() {
    return record(
        InstallationStep.FIRST_ADMIN,
        "{\"email\":\"a@b.example\",\"displayName\":\"IT\"}",
        "hash",
        true);
  }

  private static InstallationStepRecord mandatorySignIn() {
    return record(InstallationStep.SIGN_IN, "{\"mode\":\"LOCAL_ONLY\"}", null, true);
  }

  private static InstallationStepRecord record(
      InstallationStep step, String payload, String secret, boolean checked) {
    InstallationStepRecord record = new InstallationStepRecord(step);
    record.replaceValues(payload, secret, NOW);
    if (checked) {
      record.recordCheck(true, "geprüft", NOW);
    }
    return record;
  }
}
