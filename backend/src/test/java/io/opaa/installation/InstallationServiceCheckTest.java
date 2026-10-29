package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.account.InitialAdministrator;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.UserRepository;
import io.opaa.branding.BrandingSettingsService;
import io.opaa.common.ConflictException;
import io.opaa.common.FeatureFlags;
import io.opaa.common.TooManyRequestsException;
import io.opaa.directory.OidcProviderService;
import io.opaa.installation.InstallationState.CompletionSource;
import io.opaa.llm.EmbeddingModelService;
import io.opaa.llm.LlmModelService;
import io.opaa.llm.ModelRoleAdministration;
import io.opaa.mail.MailSettingsService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The check runs without any lock, so a step saved anew while its probe runs keeps the new values
 * unchecked; and the completion refuses an embedding model step without a probed vector size.
 */
class InstallationServiceCheckTest {

  private static final Instant NOW = Instant.EPOCH;

  private final InstallationStateRepository states = mock(InstallationStateRepository.class);
  private final InstallationStepRepository steps = mock(InstallationStepRepository.class);
  private final StepRules rules = mock(StepRules.class);
  private final StepChecks checks = mock(StepChecks.class);
  private final EmbeddingModelService embeddingModels = mock(EmbeddingModelService.class);
  private InstallationService service;

  @BeforeEach
  void setUp() {
    when(states.findForUpdate()).thenReturn(Optional.of(InstallationState.pending(NOW)));
    service =
        new InstallationService(
            states,
            steps,
            rules,
            checks,
            mock(InitialAdministrator.class),
            mock(OidcProviderService.class),
            mock(LlmModelService.class),
            embeddingModels,
            mock(ModelRoleAdministration.class),
            mock(MailSettingsService.class),
            mock(BrandingSettingsService.class),
            new FeatureFlags(new MockEnvironment()),
            mock(AuditEventRecorder.class),
            mock(UserRepository.class),
            mock(DatabaseInstallationStatus.class),
            Clock.fixed(NOW, ZoneOffset.UTC),
            mock(Environment.class),
            mock(PlatformTransactionManager.class));
  }

  @Test
  void aStepSavedAnewDuringItsProbeIsNotMarkedChecked() {
    InstallationStepRecord checked =
        record(InstallationStep.EMBEDDING_MODEL, "{\"model\":\"alt\"}");
    InstallationStepRecord savedMeanwhile =
        record(InstallationStep.EMBEDDING_MODEL, "{\"model\":\"neu\"}");
    when(steps.findById(InstallationStep.EMBEDDING_MODEL)).thenReturn(Optional.of(checked));
    when(steps.findForUpdate(InstallationStep.EMBEDDING_MODEL))
        .thenReturn(Optional.of(savedMeanwhile));
    when(checks.check(eq(InstallationStep.EMBEDDING_MODEL), any(), any(), any()))
        .thenReturn(new StepChecks.Outcome(true, "erfolgreich", 1024));

    assertThatThrownBy(() -> service.check(InstallationStep.EMBEDDING_MODEL))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("während der Prüfung neu gespeichert");
    verify(steps, never()).save(any());
  }

  // regression guard: connections to mail servers from the installation are bounded per instance
  @Test
  void checksOfTheMailStepHaveABudgetAlsoWithoutTestMessage() {
    InstallationStepRecord mail = record(InstallationStep.MAIL, "{\"host\":\"smtp\"}");
    when(steps.findById(InstallationStep.MAIL)).thenReturn(Optional.of(mail));
    when(steps.findForUpdate(InstallationStep.MAIL)).thenReturn(Optional.of(mail));
    when(checks.check(eq(InstallationStep.MAIL), any(), any(), any()))
        .thenReturn(new StepChecks.Outcome(false, "nicht erreichbar"));

    for (int i = 0; i < InstallationService.MAX_MAIL_CHECKS; i++) {
      service.check(InstallationStep.MAIL);
    }

    assertThatThrownBy(() -> service.check(InstallationStep.MAIL))
        .isInstanceOfSatisfying(
            TooManyRequestsException.class,
            e -> assertThat(e.getCode()).isEqualTo(InstallationService.MAIL_CHECK_LIMIT_CODE));
    verify(checks, times(InstallationService.MAX_MAIL_CHECKS))
        .check(eq(InstallationStep.MAIL), any(), any(), any());
  }

  // regression guard: probes of model endpoints from the installation are bounded per instance
  @Test
  void checksOfAllModelStepsShareOneBudget() {
    stepsSavedAsTheyAreAsked();
    when(checks.check(any(), any(), any(), any()))
        .thenReturn(new StepChecks.Outcome(false, "nicht erreichbar"));

    for (int i = 0; i < ModelCheckBudget.MAX_MODEL_CHECKS; i++) {
      service.check(PROBED_MODEL_STEPS.get(i % PROBED_MODEL_STEPS.size()));
    }

    for (InstallationStep step : PROBED_MODEL_STEPS) {
      assertThatThrownBy(() -> service.check(step))
          .isInstanceOfSatisfying(
              TooManyRequestsException.class,
              e -> {
                assertThat(e.getCode()).isEqualTo(ModelCheckBudget.LIMIT_CODE);
                assertThat(e.retryAfterSeconds()).isPositive();
              });
    }
    verify(checks, times(ModelCheckBudget.MAX_MODEL_CHECKS)).check(any(), any(), any(), any());
  }

  @Test
  void theAnswerApiCountsEveryModelStepAndGivesBackARefusedFile() {
    stepsSavedAsTheyAreAsked();
    when(checks.check(any(), any(), any(), any()))
        .thenReturn(new StepChecks.Outcome(false, "nicht erreichbar"));
    for (int i = 0; i < ModelCheckBudget.MAX_MODEL_CHECKS - 1; i++) {
      service.check(InstallationStep.CHAT_MODEL);
    }
    when(rules.toStored(any(), any(), any())).thenReturn(new StepRules.Stored("{}", null));

    assertThatThrownBy(
            () -> service.applyAnswers(answersWithTwoModels(), CompletionSource.ANSWER_FILE_API))
        .isInstanceOfSatisfying(
            TooManyRequestsException.class,
            e -> assertThat(e.getCode()).isEqualTo(ModelCheckBudget.LIMIT_CODE));
    verify(checks, times(ModelCheckBudget.MAX_MODEL_CHECKS - 1)).check(any(), any(), any(), any());

    service.check(InstallationStep.RERANK_MODEL);
    assertThatThrownBy(() -> service.check(InstallationStep.RERANK_MODEL))
        .isInstanceOf(TooManyRequestsException.class);
  }

  @Test
  void theAnswerFileAtStartUpIsNotCounted() {
    stepsSavedAsTheyAreAsked();
    when(checks.check(any(), any(), any(), any()))
        .thenReturn(new StepChecks.Outcome(false, "nicht erreichbar"));
    for (int i = 0; i < ModelCheckBudget.MAX_MODEL_CHECKS; i++) {
      service.check(InstallationStep.CHAT_MODEL);
    }
    when(rules.toStored(any(), any(), any())).thenReturn(new StepRules.Stored("{}", null));

    assertThatThrownBy(() -> service.applyAnswersAtStartup(answersWithTwoModels()))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("nicht erreichbar");
    verify(checks).check(eq(InstallationStep.FIRST_ADMIN), any(), any(), any());
  }

  @Test
  void theCompletionRefusesAnEmbeddingModelWithoutAProbedSize() {
    JsonMapper json = JsonMapper.builder().build();
    StepRules real = new StepRules(null, null, null, null, json, null, null);
    List<InstallationStepRecord> saved =
        List.of(
            checkedRecord(
                InstallationStep.FIRST_ADMIN, "{\"email\":\"a@b.example\",\"displayName\":\"IT\"}"),
            checkedRecord(InstallationStep.SIGN_IN, "{\"mode\":\"LOCAL_ONLY\"}"),
            checkedRecord(
                InstallationStep.CHAT_MODEL,
                "{\"baseUrl\":\"http://ollama:11434/v1\",\"model\":\"qwen\"}"),
            checkedRecord(
                InstallationStep.EMBEDDING_MODEL,
                "{\"baseUrl\":\"http://ollama:11434/v1\",\"model\":\"bge-m3\"}"));
    when(steps.findAll()).thenReturn(saved);
    when(rules.firstAdminPayload(any()))
        .thenAnswer(call -> real.firstAdminPayload(call.getArgument(0)));
    when(rules.signInPayload(any())).thenAnswer(call -> real.signInPayload(call.getArgument(0)));
    when(rules.chatModelPayload(any()))
        .thenAnswer(call -> real.chatModelPayload(call.getArgument(0)));
    when(rules.embeddingModelPayload(any()))
        .thenAnswer(call -> real.embeddingModelPayload(call.getArgument(0)));

    assertThatThrownBy(() -> service.complete(CompletionSource.INSTALLATION_API))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Probeeinbettung");
    verify(embeddingModels, never()).install(any(), org.mockito.ArgumentMatchers.anyInt());
  }

  /** The model steps offered without speech input. */
  private static final List<InstallationStep> PROBED_MODEL_STEPS =
      List.of(
          InstallationStep.CHAT_MODEL,
          InstallationStep.EMBEDDING_MODEL,
          InstallationStep.RERANK_MODEL);

  private void stepsSavedAsTheyAreAsked() {
    when(steps.findById(any())).thenAnswer(call -> Optional.of(record(call.getArgument(0), "{}")));
    when(steps.findForUpdate(any()))
        .thenAnswer(call -> Optional.of(record(call.getArgument(0), "{}")));
  }

  private static List<StepInput> answersWithTwoModels() {
    return List.of(
        new StepInput.FirstAdmin("it@stadt.example", "IT", "Passwort-2026-lang"),
        new StepInput.SignIn(StepInput.SignInMode.LOCAL_ONLY, null),
        new StepInput.ChatModel("http://ollama:11434/v1", "qwen", null, null, null),
        new StepInput.RerankModel("http://reranker:80", "bge-reranker", null));
  }

  private static InstallationStepRecord record(InstallationStep step, String payload) {
    InstallationStepRecord record = new InstallationStepRecord(step);
    record.replaceValues(payload, null, NOW);
    return record;
  }

  private static InstallationStepRecord checkedRecord(InstallationStep step, String payload) {
    InstallationStepRecord record = record(step, payload);
    record.recordCheck(true, "geprüft", NOW);
    return record;
  }
}
