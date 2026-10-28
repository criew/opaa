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
 * The transcription model step hangs on the feature speech-input (ADR-0060, Entscheidung 16): with
 * it on, the completion switches the role on through the model administration; with it off, the
 * step is neither listed, saved, skipped nor completed, and a step saved while it was on holds
 * nothing up and is left out.
 */
class InstallationServiceSpeechInputTest {

  private static final Instant NOW = Instant.EPOCH;
  private static final String TRANSCRIPTION =
      "{\"baseUrl\":\"http://speaches:8000/v1\",\"model\":\"whisper\",\"language\":\"en\"}";

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
    when(rules.transcriptionModelPayload(any()))
        .thenAnswer(call -> real.transcriptionModelPayload(call.getArgument(0)));
    when(rules.view(any(), any(), any()))
        .thenAnswer(
            call -> real.view(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
  }

  @Test
  void theCompletionSwitchesTheCheckedTranscriptionModelOnThroughTheAdministration() {
    when(steps.findAll())
        .thenReturn(
            List.of(
                mandatoryAdmin(),
                mandatorySignIn(),
                record(InstallationStep.TRANSCRIPTION_MODEL, TRANSCRIPTION, "chiffrat", true)));

    service(true).complete(CompletionSource.INSTALLATION_API);

    verify(modelRoles)
        .installTranscription(
            Organization.DEFAULT_ID,
            InstallationService.SYSTEM_ACTOR,
            "http://speaches:8000/v1",
            "whisper",
            "chiffrat",
            "en");
  }

  @Test
  void aSkippedTranscriptionModelLeavesSpeechInputOffAndIsNamedAsSkipped() {
    when(steps.findAll()).thenReturn(List.of(mandatoryAdmin(), mandatorySignIn()));
    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);

    service(true).complete(CompletionSource.INSTALLATION_API);

    verify(modelRoles, never())
        .installTranscription(any(), anyString(), any(), any(), any(), any());
    verify(audit, org.mockito.Mockito.atLeastOnce()).recordSystemProcessAction(events.capture());
    assertThat(events.getAllValues().getLast().after().get("skippedSteps").toString())
        .contains("TRANSCRIPTION_MODEL");
  }

  @Test
  void withoutSpeechInputTheStepIsNotListedAndASavedOneHoldsNothingUp() {
    when(steps.findAll())
        .thenReturn(
            List.of(
                mandatoryAdmin(),
                mandatorySignIn(),
                record(InstallationStep.TRANSCRIPTION_MODEL, TRANSCRIPTION, null, false)));
    InstallationService service = service(false);

    InstallationOverview overview = service.overview();

    assertThat(overview.steps())
        .extracting(InstallationOverview.StepState::step)
        .doesNotContain(InstallationStep.TRANSCRIPTION_MODEL)
        .contains(InstallationStep.MAIL, InstallationStep.BRANDING);
    assertThat(overview.completable()).isTrue();

    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    service.complete(CompletionSource.INSTALLATION_API);

    verify(modelRoles, never())
        .installTranscription(any(), anyString(), any(), any(), any(), any());
    verify(audit, org.mockito.Mockito.atLeastOnce()).recordSystemProcessAction(events.capture());
    assertThat(events.getAllValues().getLast().after().get("skippedSteps").toString())
        .doesNotContain("TRANSCRIPTION_MODEL");
  }

  @Test
  void withoutSpeechInputTheStepCannotBeSavedCheckedOrSkipped() {
    InstallationService service = service(false);

    assertThatThrownBy(
            () ->
                service.save(
                    new StepInput.TranscriptionModel(
                        "http://speaches:8000/v1", "whisper", null, null)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("OPAA_FEATURES_SPEECH_INPUT_ENABLED");
    assertThatThrownBy(() -> service.check(InstallationStep.TRANSCRIPTION_MODEL))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> service.skip(InstallationStep.TRANSCRIPTION_MODEL))
        .isInstanceOf(ValidationException.class);
    verify(steps, never()).save(any());
    verify(rules, never()).toStored(any(), any(), any());
  }

  @Test
  void withoutSpeechInputAnAnswerIgnoresTheTranscriptionModel() {
    StepChecks checks = mock(StepChecks.class);
    when(rules.toStored(any(), any(), any()))
        .thenAnswer(
            call ->
                switch (call.<StepInput>getArgument(0).step()) {
                  case FIRST_ADMIN ->
                      new StepRules.Stored(
                          "{\"email\":\"it@stadt.example\",\"displayName\":\"IT\"}", "hash");
                  case SIGN_IN -> new StepRules.Stored("{\"mode\":\"LOCAL_ONLY\"}", null);
                  default -> new StepRules.Stored(TRANSCRIPTION, null);
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
                    new StepInput.TranscriptionModel(
                        "http://speaches:8000/v1", "whisper", null, null)),
                CompletionSource.ANSWER_FILE_API);

    assertThat(completed.pending()).isFalse();
    verify(checks, never()).check(eq(InstallationStep.TRANSCRIPTION_MODEL), any(), any(), any());
    verify(rules, never()).toStored(any(StepInput.TranscriptionModel.class), any(), any());
    verify(modelRoles, never())
        .installTranscription(any(), anyString(), any(), any(), any(), any());
  }

  private InstallationService service(boolean speechInput) {
    return service(speechInput, mock(StepChecks.class));
  }

  private InstallationService service(boolean speechInput, StepChecks checks) {
    return new InstallationService(
        states,
        steps,
        rules,
        checks,
        mock(InitialAdministrator.class),
        mock(OidcProviderService.class),
        mock(LlmModelService.class),
        mock(EmbeddingModelService.class),
        mock(MailSettingsService.class),
        mock(BrandingSettingsService.class),
        modelRoles,
        new FeatureFlags(
            new MockEnvironment()
                .withProperty("opaa.features.speech-input.enabled", "" + speechInput)),
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
