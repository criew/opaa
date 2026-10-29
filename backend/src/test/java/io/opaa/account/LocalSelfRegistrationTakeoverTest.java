package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.account.LocalActionTokenService.IssuedActionToken;
import io.opaa.api.types.LocalAccountState;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.FieldValidationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Pre-hijacking of a self-registration (ADR-0033, Entscheidung 11): whoever registers somebody
 * else's address first must not decide the password the address owner later confirms. The
 * registration stores no password, and the verification link sets it - discarding any hash stored
 * before - and ends every session of the account. Repositories are in-memory stand-ins; the real
 * chain is covered by {@code LocalSelfServiceIntegrationTest}.
 */
class LocalSelfRegistrationTakeoverTest {

  private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
  private static final String ADDRESS = "erika@stadt.example";
  private static final String FOREIGN_HASH = "{bcrypt}des-angreifers";
  private static final String OWN_PASSWORD = "Tannenwipfel-Uferweg-63";

  private final UserRepository users = mock(UserRepository.class);
  private final LocalCredentialsRepository credentials = mock(LocalCredentialsRepository.class);
  private final LocalAuthSettingsRepository settings = mock(LocalAuthSettingsRepository.class);
  private final LocalActionTokenService actionTokens = mock(LocalActionTokenService.class);
  private final AuditEventRecorder audit = mock(AuditEventRecorder.class);
  private final LocalAccountMailer mailer = mock(LocalAccountMailer.class);
  private final PasswordEncoder encoder = mock(PasswordEncoder.class);
  private final LocalTokenRevocationService revocation = mock(LocalTokenRevocationService.class);
  private final LocalRefreshTokenService refreshTokens = mock(LocalRefreshTokenService.class);
  private final AtomicReference<User> user = new AtomicReference<>();
  private final AtomicReference<LocalCredentials> row = new AtomicReference<>();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private LocalSelfServiceAccountService accounts;
  private LocalPasswordService passwords;

  @BeforeEach
  void setUp() {
    when(users.findByIssuerAndEmailIgnoreCase(anyString(), anyString()))
        .thenAnswer(call -> Optional.ofNullable(user.get()));
    when(users.saveAndFlush(any(User.class)))
        .thenAnswer(
            call -> {
              user.set(call.getArgument(0));
              return user.get();
            });
    when(users.findById(any(UUID.class))).thenAnswer(call -> Optional.ofNullable(user.get()));
    when(credentials.save(any(LocalCredentials.class)))
        .thenAnswer(
            call -> {
              row.set(call.getArgument(0));
              return row.get();
            });
    when(credentials.findById(any(UUID.class))).thenAnswer(call -> Optional.ofNullable(row.get()));
    when(settings.findSingleton()).thenReturn(Optional.empty());
    when(audit.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());
    when(actionTokens.issue(any(UUID.class), eq(ActionTokenPurpose.VERIFY_EMAIL), any()))
        .thenReturn(new IssuedActionToken("roh", NOW.plus(Duration.ofHours(24))));
    when(actionTokens.redeem("roh", ActionTokenPurpose.VERIFY_EMAIL))
        .thenAnswer(
            call ->
                Optional.of(
                    new LocalActionToken(
                        user.get().getId(),
                        ActionTokenPurpose.VERIFY_EMAIL,
                        "hash",
                        NOW,
                        NOW.plus(Duration.ofHours(24)))));
    when(encoder.encode(anyString())).thenAnswer(call -> "{enc}" + call.getArgument(0));
    accounts =
        new LocalSelfServiceAccountService(
            users, credentials, settings, actionTokens, audit, mailer, clock);
    passwords =
        new LocalPasswordService(
            credentials,
            users,
            actionTokens,
            encoder,
            new PasswordPolicy(() -> 12),
            revocation,
            refreshTokens,
            audit,
            clock);
  }

  @Test
  void aRegistrationStoresNoPasswordAndCannotSignIn() {
    accounts.register(ADDRESS, "Erika");

    assertThat(row.get().getPasswordHash()).isNull();
    assertThat(row.get().getEmailVerifiedAt()).isNull();
    assertThat(row.get().state(NOW)).isEqualTo(LocalAccountState.INVITED);
    verify(mailer).sendRegistrationVerification(eq(user.get()), any());
  }

  // regression guard for #3357: a hash stored before the confirmation never survives it
  @Test
  void theVerificationLinkSetsThePasswordOfItsHolderAndDiscardsAnEarlierHash() {
    accounts.register(ADDRESS, "Erika");
    // a hash already stored - by an older registration or an administrative reset
    row.get().setPasswordHash(FOREIGN_HASH, NOW);

    passwords.confirmRegistration("roh", OWN_PASSWORD);

    assertThat(row.get().getPasswordHash()).isEqualTo("{enc}" + OWN_PASSWORD);
    assertThat(row.get().getEmailVerifiedAt()).isEqualTo(NOW);
    assertThat(row.get().state(NOW)).isEqualTo(LocalAccountState.ACTIVE);
    UUID id = user.get().getId();
    verify(revocation).invalidateSessionsIssuedBefore(id);
    verify(refreshTokens).revokeAllForUser(id, RevocationReason.PASSWORD_CHANGED);
    verify(actionTokens).consumeOpen(id, ActionTokenPurpose.SET_PASSWORD);
    verify(actionTokens).consumeOpen(id, ActionTokenPurpose.RESET_PASSWORD);
  }

  @Test
  void aPasswordThePolicyRefusesConfirmsNothingAndEndsNoSession() {
    accounts.register(ADDRESS, "Erika");

    assertThatThrownBy(() -> passwords.confirmRegistration("roh", "kurz"))
        .isInstanceOf(FieldValidationException.class);

    assertThat(row.get().getPasswordHash()).isNull();
    assertThat(row.get().getEmailVerifiedAt()).isNull();
    verify(revocation, never()).invalidateSessionsIssuedBefore(any());
    verify(refreshTokens, never()).revokeAllForUser(any(), any());
  }

  @Test
  void anUnknownVerificationLinkIsRefusedLikeEveryOtherInvalidLink() {
    accounts.register(ADDRESS, "Erika");
    when(actionTokens.redeem("fremd", ActionTokenPurpose.VERIFY_EMAIL))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> passwords.confirmRegistration("fremd", OWN_PASSWORD))
        .hasMessage(LocalActionTokenService.invalidToken().getMessage());

    assertThat(row.get().getPasswordHash()).isNull();
    verify(encoder, never()).encode(anyString());
  }
}
