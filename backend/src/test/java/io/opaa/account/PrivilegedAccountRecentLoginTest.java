package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.LocalAdminAvailabilityGuard;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.OidcProviderRepository;
import io.opaa.auth.RecentLoginPolicy;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.UserService;
import io.opaa.common.AccessDeniedException;
import io.opaa.organization.Organization;
import io.opaa.security.PasswordGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Every write that grants a system role, or could hand an account holding one to someone else, asks
 * {@link RecentLoginPolicy} before it writes anything: without a recent sign-in the account and its
 * credentials stay untouched.
 */
class PrivilegedAccountRecentLoginTest {

  private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");

  private final UserRepository users = mock(UserRepository.class);
  private final LocalCredentialsRepository credentials = mock(LocalCredentialsRepository.class);
  private final LocalActionTokenService actionTokens = mock(LocalActionTokenService.class);
  private final UserService userService = mock(UserService.class);
  private final PasswordGenerator passwordGenerator = mock(PasswordGenerator.class);
  private final OidcProviderRepository providers = mock(OidcProviderRepository.class);
  private final RecentLoginPolicy recentLogin = mock(RecentLoginPolicy.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private final LocalUserService localUsers =
      new LocalUserService(
          users,
          credentials,
          mock(LocalAuthSettingsRepository.class),
          actionTokens,
          mock(LocalRefreshTokenRepository.class),
          mock(LocalAdminAvailabilityGuard.class),
          userService,
          mock(PasswordEncoder.class),
          passwordGenerator,
          mock(AuditEventRecorder.class),
          mock(ApplicationEventPublisher.class),
          recentLogin,
          clock);

  private final LocalHandoverAccountService handovers =
      new LocalHandoverAccountService(
          users,
          credentials,
          actionTokens,
          mock(LocalActionTokenRepository.class),
          mock(LocalRefreshTokenRepository.class),
          mock(LocalRevokedTokenRepository.class),
          mock(LocalAdminAvailabilityGuard.class),
          providers,
          mock(LocalAccountSpaceDirectory.class),
          mock(LocalAccountGroupDirectory.class),
          mock(AuditEventRecorder.class),
          mock(ApplicationEventPublisher.class),
          recentLogin,
          clock);

  private final CurrentUser admin =
      CurrentUser.of(
          UUID.randomUUID(), Organization.DEFAULT_ID, SystemRole.SYSTEM_ADMIN, "Verwaltung");
  private User auditor;

  @BeforeEach
  void anAuditorAccountAndNoRecentSignIn() {
    auditor = User.localAccount("revision@stadt.example", "Revision");
    auditor.setOrganizationId(Organization.DEFAULT_ID);
    auditor.setSystemRole(SystemRole.AUDITOR);
    when(users.findByIdAndOrganizationId(auditor.getId(), Organization.DEFAULT_ID))
        .thenReturn(Optional.of(auditor));
    when(credentials.findById(auditor.getId()))
        .thenReturn(Optional.of(new LocalCredentials(auditor.getId(), "Prüfung", NOW)));
    doThrow(new AccessDeniedException("Bitte bestätigen Sie Ihre Identität."))
        .when(recentLogin)
        .requireRecentLogin();
  }

  @Test
  void creatingAnAccountWithASystemRoleIsRefused() {
    assertThatThrownBy(
            () ->
                localUsers.create(
                    admin,
                    new LocalUserCreation(
                        "neu@stadt.example",
                        "Neu",
                        SystemRole.SYSTEM_ADMIN,
                        null,
                        true,
                        "Probe",
                        false)))
        .isInstanceOf(AccessDeniedException.class);
    verify(users, never()).save(any());
    verifyNoInteractions(userService);
  }

  @Test
  void changingTheRoleIsRefused() {
    assertThatThrownBy(
            () ->
                localUsers.update(
                    admin,
                    auditor.getId(),
                    new LocalUserUpdate(
                        null, "Umbenannt", SystemRole.SYSTEM_ADMIN, null, false, null)))
        .isInstanceOf(AccessDeniedException.class);
    verify(users, never()).save(any());
    verifyNoInteractions(userService);
  }

  @Test
  void movingAPrivilegedAccountToAnotherAddressIsRefused() {
    assertThatThrownBy(
            () ->
                localUsers.update(
                    admin,
                    auditor.getId(),
                    new LocalUserUpdate("andere@stadt.example", null, null, null, false, null)))
        .isInstanceOf(AccessDeniedException.class);
    verify(users, never()).save(any());
  }

  @Test
  void aResetLinkForAPrivilegedAccountIsRefused() {
    assertThatThrownBy(() -> localUsers.issueResetLink(admin, auditor.getId()))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(actionTokens);
  }

  @Test
  void aGeneratedPasswordForAPrivilegedAccountIsRefused() {
    assertThatThrownBy(() -> localUsers.generatePassword(admin, auditor.getId()))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(passwordGenerator);
    verify(credentials, never()).save(any());
  }

  @Test
  void handingAPrivilegedAccountToAProviderIsRefused() {
    assertThatThrownBy(
            () ->
                handovers.start(
                    admin, auditor.getId(), UUID.randomUUID(), "Wechsel ins Verzeichnis"))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(actionTokens, providers);
  }
}
