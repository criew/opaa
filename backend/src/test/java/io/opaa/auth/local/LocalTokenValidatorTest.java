package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The revocation validator of the local issuer (ADR-0033, Entscheidung 8): after signature, issuer
 * and expiry it checks the {@code jti} denylist, {@code password_invalidated_before}, the derived
 * account state and the management switch - and every refusal names its reason the way the SPA
 * shows it: {@code local_accounts_disabled}, {@code account_locked} with the lock's cause, {@code
 * account_expired}, {@code session_revoked} with the revocation's cause. A structurally valid token
 * of an unknown account is refused too - the local issuer never provisions.
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
  void refusesATokenIssuedBeforeThePasswordInvalidationWithTheRevocationsCause() {
    row.invalidateSessionsIssuedBefore(NOW.minusSeconds(30), NOW);
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

  /**
   * The cutoff is the next whole second: a token minted in the same second as the invalidation is
   * refused too (iat has no finer resolution), the replacement token minted at the cutoff passes.
   */
  @Test
  void aTokenIssuedInTheSameSecondAsTheInvalidationIsRefusedAndOneAtTheCutoffPasses() {
    Instant invalidation = NOW.plusMillis(300);
    row.invalidateSessionsIssuedBefore(
        LocalTokenRevocationService.cutoffFor(invalidation), invalidation);

    assertThat(validator.rejectionFor(token(user.getId(), NOW))).isPresent();
    assertThat(validator.rejectionFor(token(user.getId(), NOW.minusSeconds(1)))).isPresent();
    assertThat(validator.rejectionFor(token(user.getId(), NOW.plusSeconds(1)))).isEmpty();
    assertThat(LocalTokenRevocationService.cutoffFor(invalidation)).isEqualTo(NOW.plusSeconds(1));
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
    row.invalidateSessionsIssuedBefore(NOW, NOW);

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
    assertThat(LocalTokenRejection.causeOf(RevocationReason.ADMIN)).isEqualTo("admin_reset");
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

  private static Jwt token(UUID subject, Instant issuedAt) {
    return Jwt.withTokenValue("t")
        .header("alg", "HS256")
        .issuer(LocalIssuer.URN)
        .subject(subject.toString())
        .jti(UUID.randomUUID().toString())
        .issuedAt(issuedAt)
        .expiresAt(issuedAt.plus(Duration.ofMinutes(15)))
        .build();
  }

  private static User localUser(SystemRole role) {
    UUID id = UUID.randomUUID();
    User user = new User(id.toString(), LocalIssuer.URN, "erika@stadt.example", "Erika");
    user.setOrganizationId(Organization.DEFAULT_ID);
    user.setSystemRole(role);
    return user;
  }
}
