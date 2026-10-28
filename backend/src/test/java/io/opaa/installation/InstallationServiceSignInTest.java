package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.account.InitialAdministrator;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.UserRepository;
import io.opaa.branding.BrandingSettingsService;
import io.opaa.directory.OidcProviderDraft;
import io.opaa.directory.OidcProviderService;
import io.opaa.installation.InstallationState.CompletionSource;
import io.opaa.llm.EmbeddingModelService;
import io.opaa.llm.LlmModelService;
import io.opaa.mail.MailSettingsService;
import io.opaa.organization.Organization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Both sign-in methods of the installation switch the local accounts on (ADR-0060, Entscheidung 9)
 * through the provider administration, under the installation's system process; the method with a
 * provider also creates it as the default.
 */
class InstallationServiceSignInTest {

  private static final Instant NOW = Instant.EPOCH;
  private static final String ISSUER = "https://idp.stadt.example/realms/opaa";

  private final InstallationStateRepository states = mock(InstallationStateRepository.class);
  private final InstallationStepRepository steps = mock(InstallationStepRepository.class);
  private final StepRules rules = mock(StepRules.class);
  private final OidcProviderService oidcProviders = mock(OidcProviderService.class);
  private InstallationService service;

  @BeforeEach
  void setUp() {
    when(states.findForUpdate()).thenReturn(Optional.of(InstallationState.pending(NOW)));
    StepRules real =
        new StepRules(null, null, null, null, JsonMapper.builder().build(), null, null);
    when(rules.firstAdminPayload(any()))
        .thenAnswer(call -> real.firstAdminPayload(call.getArgument(0)));
    when(rules.signInPayload(any())).thenAnswer(call -> real.signInPayload(call.getArgument(0)));
    when(rules.chatModelPayload(any()))
        .thenAnswer(call -> real.chatModelPayload(call.getArgument(0)));
    when(rules.embeddingModelPayload(any()))
        .thenAnswer(call -> real.embeddingModelPayload(call.getArgument(0)));
    service =
        new InstallationService(
            states,
            steps,
            rules,
            mock(StepChecks.class),
            mock(InitialAdministrator.class),
            oidcProviders,
            mock(LlmModelService.class),
            mock(EmbeddingModelService.class),
            mock(MailSettingsService.class),
            mock(BrandingSettingsService.class),
            mock(AuditEventRecorder.class),
            mock(UserRepository.class),
            mock(DatabaseInstallationStatus.class),
            Clock.fixed(NOW, ZoneOffset.UTC),
            mock(Environment.class),
            mock(PlatformTransactionManager.class));
  }

  @Test
  void localAccountsOnlySwitchesTheLocalAccountsOnAndCreatesNoProvider() {
    savedSteps("{\"mode\":\"LOCAL_ONLY\"}");

    service.complete(CompletionSource.INSTALLATION_API);

    verify(oidcProviders)
        .enableLocalAccountsForInstallation(
            Organization.DEFAULT_ID, InstallationService.SYSTEM_ACTOR);
    verify(oidcProviders, never()).createInitialProvider(any(), any(), any());
  }

  @Test
  void localAccountsAndAProviderSwitchTheLocalAccountsOnAndCreateTheProvider() {
    savedSteps(
        "{\"mode\":\"LOCAL_AND_OIDC\",\"oidc\":{\"issuerUri\":\""
            + ISSUER
            + "\",\"clientId\":\"opaa\",\"displayName\":\"Verzeichnisdienst\"}}");

    service.complete(CompletionSource.INSTALLATION_API);

    verify(oidcProviders)
        .enableLocalAccountsForInstallation(
            Organization.DEFAULT_ID, InstallationService.SYSTEM_ACTOR);
    ArgumentCaptor<OidcProviderDraft> draft = ArgumentCaptor.forClass(OidcProviderDraft.class);
    verify(oidcProviders)
        .createInitialProvider(
            eq(Organization.DEFAULT_ID), eq(InstallationService.SYSTEM_ACTOR), draft.capture());
    assertThat(draft.getValue().issuerUri()).isEqualTo(ISSUER);
  }

  private void savedSteps(String signIn) {
    when(steps.findAll())
        .thenReturn(
            List.of(
                checkedRecord(
                    InstallationStep.FIRST_ADMIN,
                    "{\"email\":\"it@stadt.example\",\"displayName\":\"IT\"}"),
                checkedRecord(InstallationStep.SIGN_IN, signIn),
                checkedRecord(
                    InstallationStep.CHAT_MODEL,
                    "{\"baseUrl\":\"http://ollama:11434/v1\",\"model\":\"qwen\"}"),
                checkedRecord(
                    InstallationStep.EMBEDDING_MODEL,
                    "{\"baseUrl\":\"http://ollama:11434/v1\",\"model\":\"bge-m3\","
                        + "\"dimensions\":1024}")));
  }

  private static InstallationStepRecord checkedRecord(InstallationStep step, String payload) {
    InstallationStepRecord record = new InstallationStepRecord(step);
    record.replaceValues(payload, null, NOW);
    record.recordCheck(true, "geprüft", NOW);
    return record;
  }
}
