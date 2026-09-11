package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcProviderRegistry;
import io.opaa.organization.Organization;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Credential verification of the local sign-in (ADR-0033, Entscheidung 9): the address is matched
 * case-insensitively within the local issuer only; exactly one {@link PasswordEncoder#matches} runs
 * per attempt - against a fixed dummy hash when there is no account or no password - so the
 * response-time class never tells an unknown address from a wrong password; only an {@code ACTIVE}
 * account signs in, and with the management switched off only a local {@code SYSTEM_ADMIN}. Every
 * refusal is the same empty result. The failed-login counter is incremented and reset here; the
 * lockout after five attempts (#1535) hangs on {@link LocalLoginAttemptListener}.
 */
class LocalLoginServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-11T10:00:00Z");
  private static final String EMAIL = "erika.muster@stadt.example";
  private static final String HASH = "{bcrypt}$2a$12$stored";

  private final UserRepository users = mock(UserRepository.class);
  private final LocalCredentialsRepository credentials = mock(LocalCredentialsRepository.class);
  private final PasswordEncoder encoder = mock(PasswordEncoder.class);
  private final OidcProviderRegistry registry = mock(OidcProviderRegistry.class);
  private final LocalLoginAttemptListener listener = mock(LocalLoginAttemptListener.class);
  private LocalLoginService service;

  private User user;
  private LocalCredentials row;

  @BeforeEach
  void setUp() {
    service =
        new LocalLoginService(
            users,
            credentials,
            encoder,
            registry,
            List.of(listener),
            Clock.fixed(NOW, ZoneOffset.UTC));
    when(registry.localAccountsEnabled()).thenReturn(true);
    user = localUser(SystemRole.USER);
    row = activeCredentials(user);
    when(users.findByIssuerAndEmailIgnoreCase(LocalIssuer.URN, EMAIL))
        .thenReturn(Optional.of(user));
    when(credentials.findById(user.getId())).thenReturn(Optional.of(row));
    when(encoder.matches("richtig", HASH)).thenReturn(true);
  }

  @Test
  void signsInAnActiveAccountWithTheRightPasswordAndResetsNothingWhenNothingFailedBefore() {
    Optional<LocalLoginService.AuthenticatedLocalAccount> result =
        service.authenticate("  Erika.Muster@Stadt.Example ", "richtig");

    assertThat(result).isPresent();
    assertThat(result.get().user()).isSameAs(user);
    assertThat(result.get().credentials()).isSameAs(row);
    verify(users).findByIssuerAndEmailIgnoreCase(LocalIssuer.URN, EMAIL);
    verify(encoder, times(1)).matches("richtig", HASH);
    verify(listener).onLoginSucceeded(user, row, NOW);
    verify(credentials, never()).save(any());
    verify(credentials, never()).recordFailedLogin(any(), any());
  }

  @Test
  void aWrongPasswordIsRefusedCountedAndReportedToTheListener() {
    assertThat(service.authenticate(EMAIL, "falsch")).isEmpty();

    verify(encoder, times(1)).matches("falsch", HASH);
    verify(credentials).recordFailedLogin(user.getId(), NOW);
    // the listener sees the row as it is after the count, reloaded past the bulk update
    verify(listener).onPasswordRejected(user, row, NOW);
    verify(listener, never()).onLoginSucceeded(any(), any(), any());
  }

  @Test
  void anUnknownAddressStillCostsExactlyOneHashComparison() {
    when(users.findByIssuerAndEmailIgnoreCase(any(), any())).thenReturn(Optional.empty());

    assertThat(service.authenticate("niemand@stadt.example", "irgendwas")).isEmpty();

    verify(encoder, times(1)).matches(eq("irgendwas"), anyString());
    verify(encoder).matches("irgendwas", LocalLoginService.DUMMY_HASH);
    verifyNoMoreInteractions(listener);
    verify(credentials, never()).recordFailedLogin(any(), any());
  }

  @Test
  void anInvitedAccountWithoutAPasswordIsComparedAgainstTheDummyHash() {
    LocalCredentials invited = new LocalCredentials(user.getId(), "Einladung", NOW);
    invited.markEmailVerified(NOW);
    when(credentials.findById(user.getId())).thenReturn(Optional.of(invited));

    assertThat(service.authenticate(EMAIL, "irgendwas")).isEmpty();

    verify(encoder, times(1)).matches("irgendwas", LocalLoginService.DUMMY_HASH);
    verifyNoMoreInteractions(listener);
  }

  @Test
  void aLockedAccountIsRefusedEvenWithTheRightPasswordAndNotCountedAsAFailedPassword() {
    row.lock(LockReason.ADMIN, NOW.minus(Duration.ofHours(1)), null);

    assertThat(service.authenticate(EMAIL, "richtig")).isEmpty();

    verify(encoder, times(1)).matches("richtig", HASH);
    verify(credentials, never()).recordFailedLogin(any(), any());
    verify(listener, never()).onPasswordRejected(any(), any(), any());
    verify(listener, never()).onLoginSucceeded(any(), any(), any());
  }

  @Test
  void aTemporaryLockoutInTheFutureRefusesTheSignIn() {
    row.recordLockoutUntil(NOW.plus(Duration.ofMinutes(10)), NOW);

    assertThat(service.authenticate(EMAIL, "richtig")).isEmpty();
  }

  @Test
  void anExpiredAccountIsRefusedEvenWithTheRightPassword() {
    row.setExpiresAt(NOW.minus(Duration.ofDays(1)), NOW);

    assertThat(service.authenticate(EMAIL, "richtig")).isEmpty();
    verify(encoder, times(1)).matches("richtig", HASH);
  }

  @Test
  void aSuccessfulSignInResetsAnEarlierFailedLoginCount() {
    LocalCredentials withFailures = mock(LocalCredentials.class);
    when(withFailures.state(NOW)).thenReturn(LocalAccountState.ACTIVE);
    when(withFailures.getPasswordHash()).thenReturn(HASH);
    when(withFailures.getFailedLoginAttempts()).thenReturn(3);
    when(credentials.findById(user.getId())).thenReturn(Optional.of(withFailures));

    assertThat(service.authenticate(EMAIL, "richtig")).isPresent();

    // an atomic UPDATE, never a save() of the loaded entity: recordFailedLogin bypasses @Version
    verify(credentials).resetFailedLoginAttempts(user.getId(), NOW);
    verify(credentials, never()).save(any());
  }

  @Test
  void withTheManagementSwitchedOffOnlyALocalSystemAdminSignsIn() {
    when(registry.localAccountsEnabled()).thenReturn(false);

    assertThat(service.authenticate(EMAIL, "richtig")).isEmpty();
    verify(listener, never()).onLoginSucceeded(any(), any(), any());
    verify(credentials, never()).recordFailedLogin(any(), any());

    User admin = localUser(SystemRole.SYSTEM_ADMIN);
    LocalCredentials adminRow = activeCredentials(admin);
    when(users.findByIssuerAndEmailIgnoreCase(LocalIssuer.URN, EMAIL))
        .thenReturn(Optional.of(admin));
    when(credentials.findById(admin.getId())).thenReturn(Optional.of(adminRow));

    assertThat(service.authenticate(EMAIL, "richtig")).isPresent();
  }

  @Test
  void aBlankAddressOrPasswordIsRefusedWithoutTouchingTheDatabase() {
    assertThat(service.authenticate("   ", "richtig")).isEmpty();
    assertThat(service.authenticate(EMAIL, "")).isEmpty();
    assertThat(service.authenticate(null, null)).isEmpty();

    verify(users, never()).findByIssuerAndEmailIgnoreCase(any(), any());
  }

  private static User localUser(SystemRole role) {
    UUID id = UUID.randomUUID();
    User user = new User(id.toString(), LocalIssuer.URN, EMAIL, "Erika Muster");
    user.setOrganizationId(Organization.DEFAULT_ID);
    user.setSystemRole(role);
    return user;
  }

  private static LocalCredentials activeCredentials(User user) {
    LocalCredentials row = new LocalCredentials(user.getId(), "Testkonto", NOW);
    row.setPasswordHash(HASH, NOW);
    row.markEmailVerified(NOW);
    return row;
  }
}
