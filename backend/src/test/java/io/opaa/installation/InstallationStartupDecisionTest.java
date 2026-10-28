package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.account.InitialAdministrator;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.UserRepository;
import io.opaa.branding.BrandingSettingsService;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The decision of a start without answer file (ADR-0060, Entscheidung 7): a database with an
 * account or a chat model counts as in use, the {@code dev} mode installs itself with its fixed
 * chat model at the shared model address, and any other start stays pending.
 */
class InstallationStartupDecisionTest {

  private static final Instant NOW = Instant.EPOCH;
  private static final String BASE_URL = "http://ollama:11434/v1";

  private final InstallationStateRepository states = mock(InstallationStateRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final LlmModelService llmModels = mock(LlmModelService.class);
  private InstallationService service;

  @BeforeEach
  void setUp() {
    when(states.findForUpdate()).thenReturn(Optional.of(InstallationState.pending(NOW)));
    service =
        new InstallationService(
            states,
            mock(InstallationStepRepository.class),
            mock(StepRules.class),
            mock(StepChecks.class),
            mock(InitialAdministrator.class),
            mock(OidcProviderService.class),
            llmModels,
            mock(EmbeddingModelService.class),
            mock(ModelRoleAdministration.class),
            mock(MailSettingsService.class),
            mock(BrandingSettingsService.class),
            mock(AuditEventRecorder.class),
            users,
            mock(DatabaseInstallationStatus.class),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new MockEnvironment()
                .withProperty(InstallationService.DEVELOPMENT_CHAT_BASE_URL_PROPERTY, BASE_URL),
            mock(PlatformTransactionManager.class));
  }

  @Test
  void aDatabaseWithOnlyAChatModelCountsAsExisting() {
    when(users.count()).thenReturn(0L);
    when(llmModels.anyModelExists()).thenReturn(true);

    InstallationService.StartupDecision decision = service.completeAtStartupIfDue(true);

    assertThat(decision.completed()).isTrue();
    assertThat(decision.source()).isEqualTo(CompletionSource.EXISTING);
    verify(llmModels, never())
        .installActiveModel(any(), any(), any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void theDevModeInstallsItsChatModelAtTheSharedAddress() {
    InstallationService.StartupDecision decision = service.completeAtStartupIfDue(true);

    assertThat(decision.source()).isEqualTo(CompletionSource.DEVELOPMENT);
    verify(llmModels)
        .installActiveModel(
            eq(Organization.DEFAULT_ID),
            eq(InstallationService.SYSTEM_ACTOR),
            eq(InstallationService.DEVELOPMENT_CHAT_MODEL_DISPLAY_NAME),
            eq(BASE_URL),
            eq(InstallationService.DEVELOPMENT_CHAT_MODEL),
            isNull(),
            eq(InstallationService.DEVELOPMENT_CHAT_MAX_TOKENS),
            isNull());
  }

  @Test
  void anEmptyDatabaseOutsideTheDevModeStaysPending() {
    InstallationService.StartupDecision decision = service.completeAtStartupIfDue(false);

    assertThat(decision.completed()).isFalse();
    verify(llmModels, never())
        .installActiveModel(any(), anyString(), any(), any(), any(), any(), anyInt(), any());
  }
}
