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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Pre-hijacking of a self-registration (ADR-0033, Entscheidung 11): whoever registers somebody
 * else's address first must not decide the password or the name the address owner later confirms.
 * The registration stores no password, and the verification link sets name and password -
 * discarding what was stored before - ends every session and closes every other link. Repositories
 * are in-memory stand-ins; the real chain is covered by {@code LocalSelfServiceIntegrationTest}.
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
            mock(ApplicationEventPublisher.class),
            clock);
  }

  @Test
  void aRegistrationStoresNoPasswordAndNoChosenNameAndCannotSignIn() {
    accounts.register(ADDRESS);

    assertThat(user.get().getDisplayName()).isEqualTo(ADDRESS);
    assertThat(row.get().getPasswordHash()).isNull();
    assertThat(row.get().getEmailVerifiedAt()).isNull();
    assertThat(row.get().state(NOW)).isEqualTo(LocalAccountState.INVITED);
    verify(mailer).sendRegistrationVerification(eq(user.get()), any());
  }

  // regression guard for #3357: a hash or name stored before the confirmation never survives it
  @Test
  void theVerificationLinkSetsNameAndPasswordOfItsHolderAndDiscardsEarlierOnes() {
    accounts.register(ADDRESS);
    // a hash already stored - by an older registration or an administrative password
    row.get().setPasswordHash(FOREIGN_HASH, NOW);

    passwords.confirmRegistration("roh", "Erika Muster", OWN_PASSWORD);

    assertThat(row.get().getPasswordHash()).isEqualTo("{enc}" + OWN_PASSWORD);
    assertThat(user.get().getDisplayName()).isEqualTo("Erika Muster");
    assertThat(row.get().getEmailVerifiedAt()).isEqualTo(NOW);
    assertThat(row.get().state(NOW)).isEqualTo(LocalAccountState.ACTIVE);
    UUID id = user.get().getId();
    verify(revocation).invalidateSessionsIssuedBefore(id);
    verify(refreshTokens).revokeAllForUser(id, RevocationReason.PASSWORD_CHANGED);
    verify(actionTokens).consumeOpen(id, ActionTokenPurpose.SET_PASSWORD);
    verify(actionTokens).consumeOpen(id, ActionTokenPurpose.RESET_PASSWORD);
    verify(actionTokens).consumeOpen(id, ActionTokenPurpose.VERIFY_EMAIL);
  }

  @Test
  void aRefusedPasswordOrNameConfirmsNothingAndEndsNoSession() {
    accounts.register(ADDRESS);

    assertThatThrownBy(() -> passwords.confirmRegistration("roh", "Erika", "kurz"))
        .isInstanceOf(FieldValidationException.class);
    assertThatThrownBy(() -> passwords.confirmRegistration("roh", "  ", OWN_PASSWORD))
        .isInstanceOf(FieldValidationException.class);

    assertThat(row.get().getPasswordHash()).isNull();
    assertThat(row.get().getEmailVerifiedAt()).isNull();
    assertThat(user.get().getDisplayName()).isEqualTo(ADDRESS);
    verify(revocation, never()).invalidateSessionsIssuedBefore(any());
    verify(refreshTokens, never()).revokeAllForUser(any(), any());
  }

  @Test
  void anUnknownVerificationLinkIsRefusedLikeEveryOtherInvalidLink() {
    accounts.register(ADDRESS);
    when(actionTokens.redeem("fremd", ActionTokenPurpose.VERIFY_EMAIL))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> passwords.confirmRegistration("fremd", "Erika", OWN_PASSWORD))
        .hasMessage(LocalActionTokenService.invalidToken().getMessage());

    assertThat(row.get().getPasswordHash()).isNull();
    verify(encoder, never()).encode(anyString());
  }

  /**
   * An invitation the administration sends to an unconfirmed self-registration confirms the
   * address; the verification link must not stay behind as a second way to set the password of the
   * then active account, past the switch and the lifetime of the reset links.
   */
  @Test
  void anInvitationRedeemedFirstClosesTheVerificationLink() {
    accounts.register(ADDRESS);
    when(actionTokens.redeem("einladung", ActionTokenPurpose.SET_PASSWORD))
        .thenAnswer(
            call ->
                Optional.of(
                    new LocalActionToken(
                        user.get().getId(),
                        ActionTokenPurpose.SET_PASSWORD,
                        "hash",
                        NOW,
                        NOW.plus(Duration.ofHours(72)))));

    passwords.setPasswordByLink("einladung", OWN_PASSWORD);

    assertThat(row.get().getEmailVerifiedAt()).isEqualTo(NOW);
    verify(actionTokens).consumeOpen(user.get().getId(), ActionTokenPurpose.VERIFY_EMAIL);
  }

  /**
   * A reset link confirms no address. Redeemed on a still unconfirmed registration that already
   * carries a hash, it must leave the verification link open - otherwise the account could never be
   * confirmed again.
   */
  @Test
  void aResetOfAnUnconfirmedRegistrationKeepsTheVerificationLinkOpen() {
    accounts.register(ADDRESS);
    row.get().setPasswordHash(FOREIGN_HASH, NOW);
    when(actionTokens.redeem("reset", ActionTokenPurpose.SET_PASSWORD))
        .thenReturn(Optional.empty());
    when(actionTokens.redeem("reset", ActionTokenPurpose.RESET_PASSWORD))
        .thenAnswer(
            call ->
                Optional.of(
                    new LocalActionToken(
                        user.get().getId(),
                        ActionTokenPurpose.RESET_PASSWORD,
                        "hash",
                        NOW,
                        NOW.plus(Duration.ofHours(1)))));

    passwords.setPasswordByLink("reset", "Zwischenstand-Passwort-2026");

    assertThat(row.get().getEmailVerifiedAt()).isNull();
    verify(actionTokens, never()).consumeOpen(any(), eq(ActionTokenPurpose.VERIFY_EMAIL));

    passwords.confirmRegistration("roh", "Erika Muster", OWN_PASSWORD);

    assertThat(row.get().getPasswordHash()).isEqualTo("{enc}" + OWN_PASSWORD);
    assertThat(row.get().state(NOW)).isEqualTo(LocalAccountState.ACTIVE);
    verify(actionTokens).consumeOpen(user.get().getId(), ActionTokenPurpose.VERIFY_EMAIL);
  }

  @Test
  void aConfirmedAddressRefusesTheVerificationLink() {
    accounts.register(ADDRESS);
    row.get().setPasswordHash("{enc}" + OWN_PASSWORD, NOW);
    row.get().markEmailVerified(NOW);

    assertThatThrownBy(
            () -> passwords.confirmRegistration("roh", "Jemand", "anderes-sicheres-passwort-2026"))
        .hasMessage(LocalActionTokenService.invalidToken().getMessage());

    assertThat(row.get().getPasswordHash()).isEqualTo("{enc}" + OWN_PASSWORD);
    assertThat(user.get().getDisplayName()).isEqualTo(ADDRESS);
    verify(revocation, never()).invalidateSessionsIssuedBefore(any());
  }
}
