package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.opaa.auth.local.LocalRefreshTokenService.IssuedRefreshToken;
import io.opaa.auth.local.LocalRefreshTokenService.RotationResult;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Refresh-token families against Postgres (ADR-0033, Entscheidung 7): a new sign-in opens a family
 * with the idle limit and the absolute end of the account's role; a rotation revokes the presented
 * token as {@code ROTATED}, issues a successor of the same family and never moves the family's end;
 * a replay revokes the whole family as {@code REUSE_DETECTED}, ends every session of the account
 * ({@code password_invalidated_before}) and is the one refresh event that is audited; unknown and
 * expired tokens are simply unknown.
 */
@OpaaIntegrationTest
class LocalRefreshTokenServiceIntegrationTest {

  @Autowired private LocalRefreshTokenService service;
  @Autowired private LocalRefreshTokenRepository repository;
  @Autowired private LocalAuthProperties properties;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;

  private LocalAccountFixtures fixtures;
  private LocalAccount user;
  private LocalAccount admin;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    fixtures.localProvider(true);
    user = fixtures.activeUser("konto-" + UUID.randomUUID() + "@stadt.example");
    admin = fixtures.activeAdmin("admin-" + UUID.randomUUID() + "@stadt.example");
  }

  @AfterEach
  void tearDown() {
    fixtures.cleanUp();
  }

  @Test
  void aRegularAccountGetsTheRegularLimitsAndAnAdministratorTheShortOnes() {
    Instant before = Instant.now();

    IssuedRefreshToken regular = service.issue(user.user());
    IssuedRefreshToken administrative = service.issue(admin.user());

    LocalRefreshToken regularRow = service.findPresented(regular.value()).orElseThrow();
    assertThat(regularRow.getUserId()).isEqualTo(user.id());
    assertThat(regularRow.getTokenLookupHash()).isNotEqualTo(regular.value());
    assertThat(regularRow.getExpiresAt())
        .isCloseTo(before.plus(properties.refreshTokenTtl()), within(Duration.ofSeconds(5)));
    assertThat(regularRow.getFamilyExpiresAt())
        .isCloseTo(before.plus(properties.sessionMaxLifetime()), within(Duration.ofSeconds(5)));
    assertThat(regular.maxAge()).isEqualTo(properties.refreshTokenTtl());

    LocalRefreshToken adminRow = service.findPresented(administrative.value()).orElseThrow();
    assertThat(adminRow.getExpiresAt())
        .isCloseTo(before.plus(properties.adminRefreshTokenTtl()), within(Duration.ofSeconds(5)));
    assertThat(adminRow.getFamilyExpiresAt())
        .isCloseTo(
            before.plus(properties.adminSessionMaxLifetime()), within(Duration.ofSeconds(5)));
    assertThat(administrative.maxAge()).isEqualTo(properties.adminRefreshTokenTtl());
    // the raw value is 256 bits of randomness, base64url without padding
    assertThat(regular.value()).hasSize(43).doesNotContain("=", "+", "/");
  }

  @Test
  void aRotationIssuesASuccessorOfTheSameFamilyAndKeepsTheFamilysEnd() {
    IssuedRefreshToken first = service.issue(user.user());

    RotationResult result = service.rotate(first.value());

    assertThat(result).isInstanceOf(RotationResult.Rotated.class);
    RotationResult.Rotated rotated = (RotationResult.Rotated) result;
    assertThat(rotated.user().getId()).isEqualTo(user.id());
    assertThat(rotated.token().value()).isNotEqualTo(first.value());
    assertThat(rotated.token().familyId()).isEqualTo(first.familyId());

    LocalRefreshToken presented = service.findPresented(first.value()).orElseThrow();
    LocalRefreshToken successor = service.findPresented(rotated.token().value()).orElseThrow();
    assertThat(presented.getRevocationReason()).isEqualTo(RevocationReason.ROTATED);
    assertThat(presented.getRotatedToId()).isEqualTo(successor.getId());
    assertThat(successor.getFamilyId()).isEqualTo(presented.getFamilyId());
    assertThat(successor.getFamilyExpiresAt()).isEqualTo(presented.getFamilyExpiresAt());
    assertThat(successor.isActive(Instant.now())).isTrue();
  }

  @Test
  void theIdleLimitOfASuccessorNeverOutlivesTheFamily() {
    IssuedRefreshToken first = service.issue(user.user());
    LocalRefreshToken row = service.findPresented(first.value()).orElseThrow();
    // a family that ends in one hour: the successor's idle limit is capped at that end
    Instant familyEnd = Instant.now().plus(Duration.ofHours(1));
    jdbc.update(
        "UPDATE local_refresh_tokens SET family_expires_at = ? WHERE id = ?",
        java.sql.Timestamp.from(familyEnd),
        row.getId());

    RotationResult.Rotated rotated = (RotationResult.Rotated) service.rotate(first.value());

    LocalRefreshToken successor = service.findPresented(rotated.token().value()).orElseThrow();
    assertThat(successor.getExpiresAt()).isCloseTo(familyEnd, within(Duration.ofSeconds(1)));
    assertThat(rotated.token().maxAge()).isLessThanOrEqualTo(Duration.ofHours(1));
  }

  @Test
  void aReplayRevokesEveryFamilyOfTheAccountEndsEverySessionAndIsAudited() {
    long auditedBefore = auditedSessionRevocations();
    IssuedRefreshToken first = service.issue(user.user());
    IssuedRefreshToken otherFamily = service.issue(user.user());
    RotationResult.Rotated rotated = (RotationResult.Rotated) service.rotate(first.value());

    RotationResult replay = service.rotate(first.value());

    assertThat(replay).isEqualTo(new RotationResult.Reused(user.id()));
    LocalRefreshToken successor = service.findPresented(rotated.token().value()).orElseThrow();
    assertThat(successor.getRevocationReason()).isEqualTo(RevocationReason.REUSE_DETECTED);
    assertThat(successor.isActive(Instant.now())).isFalse();
    // the presented token keeps its ROTATED reason - only the still active rows change
    assertThat(service.findPresented(first.value()).orElseThrow().getRevocationReason())
        .isEqualTo(RevocationReason.ROTATED);
    // the replay of a family ends every session, so the account's access tokens die with it
    assertThat(fixtures.credentialsOf(user).getPasswordInvalidatedBefore())
        .isNotNull()
        .isCloseTo(Instant.now(), within(Duration.ofSeconds(5)));
    // every other family of the account dies with it - a replay ends the account's sessions ...
    LocalRefreshToken other = service.findPresented(otherFamily.value()).orElseThrow();
    assertThat(other.isActive(Instant.now())).isFalse();
    assertThat(other.getRevocationReason()).isEqualTo(RevocationReason.REUSE_DETECTED);
    // ... and a further presentation of the successor - revoked as REUSE_DETECTED, not ROTATED -
    // is over, not a second replay: no second warning, no second event
    assertThat(service.rotate(rotated.token().value())).isEqualTo(new RotationResult.Unknown());
    assertThat(auditedSessionRevocations()).isEqualTo(auditedBefore + 1);
  }

  /**
   * Only a rotated token presented again is a replay. A token revoked by a sign-out, a password
   * change or an administrative act is simply over: another device of the account learns that on
   * its next refresh without a warning, a family revocation or an audit event.
   */
  @Test
  void aTokenRevokedForAnotherReasonIsUnknownNotAReplay() {
    long auditedBefore = auditedSessionRevocations();
    IssuedRefreshToken thisDevice = service.issue(user.user());
    IssuedRefreshToken otherDevice = service.issue(user.user());
    service.revokeAllForUser(user.id(), RevocationReason.PASSWORD_CHANGED);
    IssuedRefreshToken fresh = service.issue(user.user());

    assertThat(service.rotate(otherDevice.value())).isEqualTo(new RotationResult.Unknown());
    assertThat(service.rotate(thisDevice.value())).isEqualTo(new RotationResult.Unknown());

    // the fresh family is untouched - no family revocation happened
    assertThat(service.rotate(fresh.value())).isInstanceOf(RotationResult.Rotated.class);
    assertThat(auditedSessionRevocations()).isEqualTo(auditedBefore);
    assertThat(service.findPresented(otherDevice.value()).orElseThrow().getRevocationReason())
        .isEqualTo(RevocationReason.PASSWORD_CHANGED);
  }

  @Test
  void unknownAndExpiredTokensAreUnknown() {
    IssuedRefreshToken issued = service.issue(user.user());
    // moved into the past as a whole - the schema insists on expires_at > issued_at
    jdbc.update(
        "UPDATE local_refresh_tokens SET issued_at = ?, expires_at = ? WHERE user_id = ?",
        java.sql.Timestamp.from(Instant.now().minusSeconds(10)),
        java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
        user.id());

    assertThat(service.rotate("kein-token")).isEqualTo(new RotationResult.Unknown());
    assertThat(service.rotate(issued.value())).isEqualTo(new RotationResult.Unknown());
    assertThat(service.revokePresentedFamily("kein-token", RevocationReason.LOGOUT)).isFalse();
  }

  /**
   * A rotation for an account that may not sign in - no longer login-capable, or a regular account
   * while the management is off - is refused like an unknown token, so the session ends instead of
   * minting tokens the validator refuses anyway.
   */
  @Test
  void aRotationIsRefusedForAnAccountThatMayNotSignIn() {
    IssuedRefreshToken expiredAccount = service.issue(user.user());
    LocalCredentials row = fixtures.credentialsOf(user);
    row.setExpiresAt(Instant.now().minusSeconds(1), Instant.now());
    fixtures.save(row);

    assertThat(service.rotate(expiredAccount.value())).isEqualTo(new RotationResult.Unknown());
    // refused, not revoked: an unlocked account rotates again
    row = fixtures.credentialsOf(user);
    row.setExpiresAt(null, Instant.now());
    fixtures.save(row);
    assertThat(service.rotate(expiredAccount.value())).isInstanceOf(RotationResult.Rotated.class);

    IssuedRefreshToken regular = service.issue(user.user());
    IssuedRefreshToken administrative = service.issue(admin.user());
    fixtures.localProvider(false);
    try {
      assertThat(service.rotate(regular.value())).isEqualTo(new RotationResult.Unknown());
      assertThat(service.rotate(administrative.value())).isInstanceOf(RotationResult.Rotated.class);
    } finally {
      fixtures.localProvider(true);
    }
  }

  @Test
  void revokingEverythingExceptOneFamilyKeepsExactlyThatFamily() {
    IssuedRefreshToken keep = service.issue(user.user());
    IssuedRefreshToken drop = service.issue(user.user());
    IssuedRefreshToken foreign = service.issue(admin.user());

    int revoked =
        service.revokeAllForUserExcept(
            user.id(), keep.familyId(), RevocationReason.PASSWORD_CHANGED);

    assertThat(revoked).isEqualTo(1);
    assertThat(service.findPresented(keep.value()).orElseThrow().isActive(Instant.now())).isTrue();
    LocalRefreshToken dropped = service.findPresented(drop.value()).orElseThrow();
    assertThat(dropped.getRevocationReason()).isEqualTo(RevocationReason.PASSWORD_CHANGED);
    assertThat(service.findPresented(foreign.value()).orElseThrow().isActive(Instant.now()))
        .isTrue();
    assertThat(service.revokePresentedFamily(keep.value(), RevocationReason.LOGOUT)).isTrue();
    assertThat(service.findPresented(keep.value()).orElseThrow().getRevocationReason())
        .isEqualTo(RevocationReason.LOGOUT);
    assertThat(repository.count()).isGreaterThanOrEqualTo(3);
  }

  private long auditedSessionRevocations() {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE event_type = 'LOCAL_SESSION_REVOKED'",
            Long.class);
    return count == null ? 0 : count;
  }
}
