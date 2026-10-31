package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.LockReason;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.OidcProviderRegistry;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The revocation validator of the local issuer (ADR-0033, Entscheidung 8): after signature, issuer
 * and expiry it checks the {@code jti} denylist, the session generation, the derived account state
 * and the management switch - and every refusal names its reason the way the SPA shows it: {@code
 * local_accounts_disabled}, {@code account_locked} with the lock's cause, {@code account_expired},
 * {@code session_revoked} with the revocation's cause. A structurally valid token of an unknown
 * account is refused too - the local issuer never provisions.
 */
class LocalTokenValidatorTest {

  private static final Instant NOW = Instant.parse("2026-09-11T10:00:00Z");

  private final LocalCredentialsRepository credentials = mock(LocalCredentialsRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final LocalRefreshTokenRepository refreshTokens = mock(LocalRefreshTokenRepository.class);
  private final LocalTokenRevocationService revocation = mock(LocalTokenRevocationService.class);
  private final OidcProviderRegistry registry = mock(OidcProviderRegistry.class);
  private LocalTokenValidator validator;

  private User user;
  private LocalCredentials row;

  @BeforeEach
  void setUp() {
    validator =
        new LocalTokenValidator(
            credentials,
            users,
            refreshTokens,
            revocation,
            registry,
            Clock.fixed(NOW, ZoneOffset.UTC));
    when(registry.localAccountsEnabled()).thenReturn(true);
    user = localUser(SystemRole.USER);
    row = new LocalCredentials(user.getId(), "Testkonto", NOW.minus(Duration.ofDays(1)));
    row.setPasswordHash("{bcrypt}x", NOW.minus(Duration.ofDays(1)));
    row.markEmailVerified(NOW.minus(Duration.ofDays(1)));
    when(credentials.findById(user.getId())).thenReturn(Optional.of(row));
    when(users.findById(user.getId())).thenReturn(Optional.of(user));
    when(refreshTokens.findFirstByUserIdAndRevocationReasonInOrderByRevokedAtDesc(any(), any()))
        .thenReturn(Optional.empty());
  }

  @Test
  void acceptsATokenOfAnActiveAccountWhoseJtiIsNotDenylisted() {
    assertThat(validator.rejectionFor(token(user.getId(), NOW.minusSeconds(60)))).isEmpty();
    // the user row is not needed while the management is enabled - one lookup per request
    verify(users, never()).findById(any());
  }

  @Test
  void refusesADenylistedJtiAsSessionRevoked() {
    Jwt jwt = token(user.getId(), NOW.minusSeconds(60));
    when(revocation.isDenylisted(jwt.getId())).thenReturn(true);

    assertThat(validator.rejectionFor(jwt))
        .contains(new LocalTokenRejection(LocalTokenMarkers.SESSION_REVOKED, null));
  }

  @Test
  void refusesATokenOfAnEndedSessionGenerationWithTheRevocationsCause() {
    row.endAllSessions(NOW);
    LocalRefreshToken revoked =
        new LocalRefreshToken(UUID.randomUUID(), user.getId(), "h", NOW.minusSeconds(40), NOW, NOW);
    revoked.revoke(RevocationReason.PASSWORD_CHANGED, NOW.minusSeconds(30));
    when(refreshTokens.findFirstByUserIdAndRevocationReasonInOrderByRevokedAtDesc(
            user.getId(), LocalTokenValidator.ACTS))
        .thenReturn(Optional.of(revoked));

    Optional<LocalTokenRejection> rejection =
        validator.rejectionFor(token(user.getId(), NOW.minusSeconds(60)));

    assertThat(rejection)
        .contains(new LocalTokenRejection(LocalTokenMarkers.SESSION_REVOKED, "password_changed"));
    assertThat(rejection.get().errorDescription()).isEqualTo("session_revoked:password_changed");
  }

  // regression guard for #2959: a token without sgen is malformed, never held against a cutoff
  @Test
  void aTokenWithoutSessionGenerationIsAMalformedToken() {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "HS256")
            .issuer(LocalIssuer.URN)
            .subject(user.getId().toString())
            .jti(UUID.randomUUID().toString())
            .issuedAt(NOW)
            .expiresAt(NOW.plus(Duration.ofMinutes(15)))
            .build();

    assertThat(validator.rejectionFor(jwt))
        .contains(new LocalTokenRejection(LocalTokenMarkers.MALFORMED_TOKEN, null));
  }

  // regression guard for ADR-0053, Entscheidung 6: no clock of any host decides a revocation
  @Test
  void aTokenOfAnEarlierSessionGenerationIsRefusedHoweverLateItsIat() {
    Jwt minted = token(user.getId(), NOW.plusSeconds(5), row.getSessionGeneration());
    row.endAllSessions(NOW);

    assertThat(validator.rejectionFor(minted))
        .contains(new LocalTokenRejection(LocalTokenMarkers.SESSION_REVOKED, null));
  }

  @Test
  void aTokenOfTheCurrentSessionGenerationPassesHoweverEarlyItsIat() {
    row.endAllSessions(NOW);

    Jwt minted = token(user.getId(), NOW.minusSeconds(5), row.getSessionGeneration());

    assertThat(validator.rejectionFor(minted)).isEmpty();
  }

  @Test
  void aSessionGenerationThatIsNoWholeNumberIsAMalformedToken() {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "HS256")
            .issuer(LocalIssuer.URN)
            .subject(user.getId().toString())
            .jti(UUID.randomUUID().toString())
            .issuedAt(NOW)
            .expiresAt(NOW.plus(Duration.ofMinutes(15)))
            .claim(LocalAccessTokenService.SESSION_GENERATION_CLAIM, "0")
            .build();

    assertThat(validator.rejectionFor(jwt))
        .contains(new LocalTokenRejection(LocalTokenMarkers.MALFORMED_TOKEN, null));
  }

  @Test
  void anAccountThatIsNotActiveIsRefusedEvenWhenNeitherLockedNorExpired() {
    LocalCredentials invited = new LocalCredentials(user.getId(), "Einladung", NOW);
    invited.setPasswordHash("{bcrypt}x", NOW);
    // address never confirmed - INVITED, fail closed
    when(credentials.findById(user.getId())).thenReturn(Optional.of(invited));

    assertThat(validator.rejectionFor(token(user.getId(), NOW.minusSeconds(60))))
        .contains(new LocalTokenRejection(LocalTokenMarkers.ACCOUNT_NOT_ACTIVE, null));
  }

  @Test
  void theCauseOfARevokedSessionSkipsRoutineRotationsAndSignOuts() {
    row.endAllSessions(NOW);

    validator.rejectionFor(token(user.getId(), NOW.minusSeconds(60)));

    verify(refreshTokens)
        .findFirstByUserIdAndRevocationReasonInOrderByRevokedAtDesc(
            user.getId(), LocalTokenValidator.ACTS);
    assertThat(LocalTokenValidator.ACTS)
        .doesNotContain(RevocationReason.ROTATED, RevocationReason.LOGOUT)
        .containsExactlyInAnyOrder(
            RevocationReason.ACCOUNT_LOCKED,
            RevocationReason.PASSWORD_CHANGED,
            RevocationReason.ADMIN_RESET,
            RevocationReason.ADMIN,
            RevocationReason.REUSE_DETECTED,
            RevocationReason.HANDED_OVER);
  }

  @Test
  void mapsEveryRevocationReasonToTheAdrsMarkerCause() {
    assertThat(LocalTokenRejection.causeOf(RevocationReason.ACCOUNT_LOCKED))
        .isEqualTo("admin_lock");
    assertThat(LocalTokenRejection.causeOf(RevocationReason.PASSWORD_CHANGED))
        .isEqualTo("password_changed");
    assertThat(LocalTokenRejection.causeOf(RevocationReason.ADMIN_RESET)).isEqualTo("admin_reset");
    // regression guard for #1595: the general administrative act is not a password reset, so it
    // must not inherit the cause whose sentence says one happened
    assertThat(LocalTokenRejection.causeOf(RevocationReason.ADMIN)).isEqualTo("admin_action");
    assertThat(LocalTokenRejection.causeOf(RevocationReason.ADMIN))
        .isNotEqualTo(LocalTokenRejection.causeOf(RevocationReason.ADMIN_RESET));
    assertThat(LocalTokenRejection.causeOf(RevocationReason.REUSE_DETECTED))
        .isEqualTo("reuse_detected");
    assertThat(LocalTokenRejection.causeOf(RevocationReason.HANDED_OVER)).isEqualTo("handed_over");
    assertThat(LocalTokenRejection.causeOf(RevocationReason.LOGOUT)).isNull();
    assertThat(LocalTokenRejection.causeOf(RevocationReason.ROTATED)).isNull();
  }

  @Test
  void refusesALockedAccountWithTheLocksCause() {
    row.lock(LockReason.INACTIVITY, NOW.minusSeconds(5), null);

    assertThat(validator.rejectionFor(token(user.getId(), NOW.minusSeconds(60))))
        .contains(new LocalTokenRejection(LocalTokenMarkers.ACCOUNT_LOCKED, "inactivity"));
  }

  @Test
  void aTemporaryLockoutCountsAsLockedAfterFailedLogins() {
    row.recordLockoutUntil(NOW.plus(Duration.ofMinutes(10)), NOW);

    assertThat(validator.rejectionFor(token(user.getId(), NOW.minusSeconds(60))))
        .contains(new LocalTokenRejection(LocalTokenMarkers.ACCOUNT_LOCKED, "failed_logins"));
  }

  @Test
  void refusesAnExpiredAccount() {
    row.setExpiresAt(NOW.minusSeconds(1), NOW);

    assertThat(validator.rejectionFor(token(user.getId(), NOW.minusSeconds(60))))
        .contains(new LocalTokenRejection(LocalTokenMarkers.ACCOUNT_EXPIRED, null));
  }

  @Test
  void refusesRegularAccountsWhileTheManagementIsOffButNotSystemAdmins() {
    when(registry.localAccountsEnabled()).thenReturn(false);

    assertThat(validator.rejectionFor(token(user.getId(), NOW.minusSeconds(60))))
        .contains(new LocalTokenRejection(LocalTokenMarkers.LOCAL_ACCOUNTS_DISABLED, null));

    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    assertThat(validator.rejectionFor(token(user.getId(), NOW.minusSeconds(60)))).isEmpty();
  }

  /**
   * regression guard for #1595: switching the management off revokes the running sessions of
   * regular accounts on the way, so the switch has to outrank those revocations - otherwise the
   * marker of ADR-0033, Entscheidung 4 is unreachable and the person reads the revocation's cause
   * instead.
   */
  @Test
  void theSwitchOutranksARevocationThatTheSwitchingOffItselfCaused() {
    when(registry.localAccountsEnabled()).thenReturn(false);
    Jwt jwt = token(user.getId(), NOW.minusSeconds(60));
    when(revocation.isDenylisted(jwt.getId())).thenReturn(true);
    row.endAllSessions(NOW);
    LocalRefreshToken revoked =
        new LocalRefreshToken(UUID.randomUUID(), user.getId(), "h", NOW.minusSeconds(40), NOW, NOW);
    revoked.revoke(RevocationReason.ADMIN, NOW);
    when(refreshTokens.findFirstByUserIdAndRevocationReasonInOrderByRevokedAtDesc(
            user.getId(), LocalTokenValidator.ACTS))
        .thenReturn(Optional.of(revoked));

    assertThat(validator.rejectionFor(jwt))
        .contains(new LocalTokenRejection(LocalTokenMarkers.LOCAL_ACCOUNTS_DISABLED, null));

    // the administrator passes the switch and therefore still reads the act that ended the session
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    assertThat(validator.rejectionFor(jwt))
        .contains(new LocalTokenRejection(LocalTokenMarkers.SESSION_REVOKED, "admin_action"));
  }

  /**
   * regression guard for #1595: a handed-over account (ADR-0033, Entscheidung 12) carries the
   * provider's issuer and has no credentials row; the switch of the *local* management does not
   * govern it. It must keep the refusal that names the handover with the switch off too - that is
   * the recommended operating order (provider, hand over, switch the local management off), and
   * local_accounts_disabled would hide the one way in the person still has.
   */
  @Test
  void aHandedOverAccountKeepsItsCauseWhileTheManagementIsOff() {
    user.handOverTo("https://idp.example/realms/stadt", "sub-42");
    when(credentials.findById(user.getId())).thenReturn(Optional.empty());
    LocalRefreshToken revoked =
        new LocalRefreshToken(UUID.randomUUID(), user.getId(), "h", NOW.minusSeconds(40), NOW, NOW);
    revoked.revoke(RevocationReason.HANDED_OVER, NOW);
    when(refreshTokens.findFirstByUserIdAndRevocationReasonInOrderByRevokedAtDesc(
            user.getId(), LocalTokenValidator.ACTS))
        .thenReturn(Optional.of(revoked));
    Jwt jwt = token(user.getId(), NOW.minusSeconds(60));

    assertThat(validator.rejectionFor(jwt))
        .contains(
            new LocalTokenRejection(
                LocalTokenMarkers.SESSION_REVOKED, LocalTokenRejection.HANDED_OVER_CAUSE));

    when(registry.localAccountsEnabled()).thenReturn(false);
    assertThat(validator.rejectionFor(jwt))
        .contains(
            new LocalTokenRejection(
                LocalTokenMarkers.SESSION_REVOKED, LocalTokenRejection.HANDED_OVER_CAUSE));
  }

  @Test
  void refusesATokenOfAnUnknownAccountWithoutCreatingAnything() {
    UUID unknown = UUID.randomUUID();
    when(credentials.findById(unknown)).thenReturn(Optional.empty());

    assertThat(validator.rejectionFor(token(unknown, NOW.minusSeconds(60))))
        .contains(new LocalTokenRejection(LocalTokenMarkers.UNKNOWN_ACCOUNT, null));
    verify(users, never()).save(any());
  }

  @Test
  void refusesATokenWithoutJtiSubjectOrIssuedAtAsMalformed() {
    Jwt withoutJti =
        Jwt.withTokenValue("t")
            .header("alg", "HS256")
            .issuer(LocalIssuer.URN)
            .subject(user.getId().toString())
            .issuedAt(NOW.minusSeconds(60))
            .expiresAt(NOW.plusSeconds(60))
            .build();
    Jwt nonUuidSubject =
        Jwt.withTokenValue("t")
            .header("alg", "HS256")
            .issuer(LocalIssuer.URN)
            .subject("alice")
            .jti(UUID.randomUUID().toString())
            .issuedAt(NOW.minusSeconds(60))
            .expiresAt(NOW.plusSeconds(60))
            .build();

    assertThat(validator.rejectionFor(withoutJti))
        .contains(new LocalTokenRejection(LocalTokenMarkers.MALFORMED_TOKEN, null));
    assertThat(validator.rejectionFor(nonUuidSubject))
        .contains(new LocalTokenRejection(LocalTokenMarkers.MALFORMED_TOKEN, null));
  }

  /** A token as this version mints it, with the session generation it was minted under. */
  private static Jwt token(UUID subject, Instant issuedAt, long sessionGeneration) {
    return Jwt.withTokenValue("t")
        .header("alg", "HS256")
        .issuer(LocalIssuer.URN)
        .subject(subject.toString())
        .jti(UUID.randomUUID().toString())
        .issuedAt(issuedAt)
        .expiresAt(issuedAt.plus(Duration.ofMinutes(15)))
        .claim(LocalAccessTokenService.SESSION_GENERATION_CLAIM, sessionGeneration)
        .build();
  }

  /** A token minted before any session of the account ended (session generation 0). */
  private static Jwt token(UUID subject, Instant issuedAt) {
    return token(subject, issuedAt, 0L);
  }

  private static User localUser(SystemRole role) {
    UUID id = UUID.randomUUID();
    User user = new User(id.toString(), LocalIssuer.URN, "erika@stadt.example", "Erika");
    user.setOrganizationId(Organization.DEFAULT_ID);
    user.setSystemRole(role);
    return user;
  }
}
