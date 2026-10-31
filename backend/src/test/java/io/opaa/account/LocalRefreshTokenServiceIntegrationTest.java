package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.opaa.account.LocalRefreshTokenService.IssuedRefreshToken;
import io.opaa.account.LocalRefreshTokenService.RotationResult;
import io.opaa.auth.LocalCredentials;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Refresh-token families against Postgres (ADR-0033, Entscheidung 7): a new sign-in opens a family
 * with the idle limit and the absolute end of the account's role; a rotation revokes the presented
 * token as {@code ROTATED}, issues a successor of the same family and never moves the family's end;
 * a rotated token presented again within the grace period while its successor is unused is renewed
 * once more; any other presentation of a rotated token is a replay that revokes the whole family as
 * {@code REUSE_DETECTED}, ends every session of the account (its session generation) and is the one
 * refresh event that is audited; unknown and expired tokens are simply unknown.
 */
@OpaaIntegrationTest
class LocalRefreshTokenServiceIntegrationTest {

  @Autowired private LocalRefreshTokenService service;
  @Autowired private LocalRefreshTokenRepository repository;
  @Autowired private LocalAuthProperties properties;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
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

    RotationResult result = service.rotate(first.value(), null);

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

    RotationResult.Rotated rotated = (RotationResult.Rotated) service.rotate(first.value(), null);

    LocalRefreshToken successor = service.findPresented(rotated.token().value()).orElseThrow();
    assertThat(successor.getExpiresAt()).isCloseTo(familyEnd, within(Duration.ofSeconds(1)));
    assertThat(rotated.token().maxAge()).isLessThanOrEqualTo(Duration.ofHours(1));
  }

  @Test
  void aReplayRevokesEveryFamilyOfTheAccountEndsEverySessionAndIsAudited() {
    long auditedBefore = auditedSessionRevocations();
    IssuedRefreshToken first = service.issue(user.user());
    IssuedRefreshToken otherFamily = service.issue(user.user());
    RotationResult.Rotated used = (RotationResult.Rotated) service.rotate(first.value(), null);
    RotationResult.Rotated rotated =
        (RotationResult.Rotated) service.rotate(used.token().value(), null);

    RotationResult replay = service.rotate(first.value(), null);

    assertThat(replay).isEqualTo(new RotationResult.Reused(user.id()));
    LocalRefreshToken successor = service.findPresented(rotated.token().value()).orElseThrow();
    assertThat(successor.getRevocationReason()).isEqualTo(RevocationReason.REUSE_DETECTED);
    assertThat(successor.isActive(Instant.now())).isFalse();
    // the presented token keeps its ROTATED reason - only the still active rows change
    assertThat(service.findPresented(first.value()).orElseThrow().getRevocationReason())
        .isEqualTo(RevocationReason.ROTATED);
    // the replay of a family ends every session, so the account's access tokens die with it
    assertThat(fixtures.credentialsOf(user).getSessionGeneration()).isPositive();
    // every other family of the account dies with it - a replay ends the account's sessions ...
    LocalRefreshToken other = service.findPresented(otherFamily.value()).orElseThrow();
    assertThat(other.isActive(Instant.now())).isFalse();
    assertThat(other.getRevocationReason()).isEqualTo(RevocationReason.REUSE_DETECTED);
    // ... and a further presentation of the successor - revoked as REUSE_DETECTED, not ROTATED -
    // is over, not a second replay: no second warning, no second event
    assertThat(service.rotate(rotated.token().value(), null))
        .isEqualTo(new RotationResult.Unknown());
    assertThat(auditedSessionRevocations()).isEqualTo(auditedBefore + 1);
  }

  /**
   * The reload that discarded the answer of a refresh presents the rotated token again: within the
   * grace period and with its successor never used, the unused successor is rotated away and a new
   * one issued - no replay, no other session touched, no audit event. Twice in a row as well, the
   * double reload (regression guard for #3062).
   */
  @Test
  void aRotatedTokenPresentedAgainWithinTheGracePeriodIsRenewedNotAReplay() {
    long auditedBefore = auditedSessionRevocations();
    IssuedRefreshToken first = service.issue(user.user());
    IssuedRefreshToken otherFamily = service.issue(user.user());
    RotationResult.Rotated discarded = (RotationResult.Rotated) service.rotate(first.value(), null);

    RotationResult reload = service.rotate(first.value(), null);

    assertThat(reload).isInstanceOf(RotationResult.Rotated.class);
    RotationResult.Rotated renewed = (RotationResult.Rotated) reload;
    assertThat(renewed.token().familyId()).isEqualTo(first.familyId());
    LocalRefreshToken replaced = service.findPresented(discarded.token().value()).orElseThrow();
    assertThat(replaced.getRevocationReason()).isEqualTo(RevocationReason.ROTATED);
    assertThat(replaced.getRotatedToId()).isEqualTo(renewed.token().id());
    assertThat(service.findPresented(first.value()).orElseThrow().getRotatedToId())
        .isEqualTo(renewed.token().id());

    RotationResult.Rotated secondReload =
        (RotationResult.Rotated) service.rotate(first.value(), null);

    assertThat(activeTokensOfFamily(first.familyId())).containsExactly(secondReload.token().id());
    assertThat(service.findPresented(otherFamily.value()).orElseThrow().isActive(Instant.now()))
        .isTrue();
    assertThat(fixtures.credentialsOf(user).getSessionGeneration()).isZero();
    assertThat(auditedSessionRevocations()).isEqualTo(auditedBefore);
    // the renewed session carries on with its own token
    assertThat(service.rotate(secondReload.token().value(), null))
        .isInstanceOf(RotationResult.Rotated.class);
  }

  @Test
  void aRotatedTokenWhoseSuccessorWasUsedIsAReplayEvenWithinTheGracePeriod() {
    IssuedRefreshToken first = service.issue(user.user());
    IssuedRefreshToken otherFamily = service.issue(user.user());
    RotationResult.Rotated used = (RotationResult.Rotated) service.rotate(first.value(), null);
    service.rotate(used.token().value(), null);

    assertThat(service.rotate(first.value(), null)).isEqualTo(new RotationResult.Reused(user.id()));
    assertThat(activeTokensOfFamily(first.familyId())).isEmpty();
    assertThat(service.findPresented(otherFamily.value()).orElseThrow().getRevocationReason())
        .isEqualTo(RevocationReason.REUSE_DETECTED);
  }

  @Test
  void aRotatedTokenPresentedAfterTheGracePeriodIsAReplay() {
    long auditedBefore = auditedSessionRevocations();
    IssuedRefreshToken first = service.issue(user.user());
    service.rotate(first.value(), null);
    LocalRefreshToken row = service.findPresented(first.value()).orElseThrow();
    Instant past = Instant.now().minus(properties.refreshGracePeriod()).minusSeconds(1);
    jdbc.update(
        "UPDATE local_refresh_tokens SET issued_at = ?, revoked_at = ? WHERE id = ?",
        java.sql.Timestamp.from(past.minusSeconds(1)),
        java.sql.Timestamp.from(past),
        row.getId());

    assertThat(service.rotate(first.value(), null)).isEqualTo(new RotationResult.Reused(user.id()));
    assertThat(activeTokensOfFamily(first.familyId())).isEmpty();
    assertThat(auditedSessionRevocations()).isEqualTo(auditedBefore + 1);
  }

  @Test
  void theGracePeriodIsRefusedForAnAccountThatMayNoLongerSignIn() {
    IssuedRefreshToken first = service.issue(user.user());
    RotationResult.Rotated discarded = (RotationResult.Rotated) service.rotate(first.value(), null);
    LocalCredentials row = fixtures.credentialsOf(user);
    row.setExpiresAt(Instant.now().minusSeconds(1), Instant.now());
    fixtures.save(row);

    assertThat(service.rotate(first.value(), null)).isEqualTo(new RotationResult.Reused(user.id()));
    assertThat(
            service.findPresented(discarded.token().value()).orElseThrow().isActive(Instant.now()))
        .isFalse();
  }

  /**
   * Two renewals of the same rotated token racing for its unused successor: exactly one wins, the
   * other is a replay - the grace period never hands out two live successors.
   */
  @Test
  void twoConcurrentRenewalsWithinTheGracePeriodNeverBothWin() throws Exception {
    IssuedRefreshToken first = service.issue(user.user());
    RotationResult.Rotated discarded = (RotationResult.Rotated) service.rotate(first.value(), null);

    List<RotationResult> results =
        raceWhileLocked(discarded.token().id(), () -> service.rotate(first.value(), null));

    assertThat(results).filteredOn(RotationResult.Rotated.class::isInstance).hasSize(1);
    assertThat(results).contains(new RotationResult.Reused(user.id()));
    assertThat(activeTokensOfFamily(first.familyId())).isEmpty();
  }

  /**
   * The reload that sends the old cookie while the first refresh is still running loses the atomic
   * rotation; it is then judged like the rotated token it presented, so both answers carry a
   * session and the family keeps exactly one live token.
   */
  @Test
  void theLoserOfAConcurrentRotationIsRenewedWithinTheGracePeriod() throws Exception {
    IssuedRefreshToken first = service.issue(user.user());
    LocalRefreshToken row = service.findPresented(first.value()).orElseThrow();

    List<RotationResult> results =
        raceWhileLocked(row.getId(), () -> service.rotate(first.value(), null));

    assertThat(results).allMatch(RotationResult.Rotated.class::isInstance);
    UUID last =
        results.stream()
            .map(result -> ((RotationResult.Rotated) result).token().id())
            .filter(id -> repository.findById(id).orElseThrow().isActive(Instant.now()))
            .findFirst()
            .orElseThrow();
    assertThat(activeTokensOfFamily(first.familyId())).containsExactly(last);
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

    assertThat(service.rotate(otherDevice.value(), null)).isEqualTo(new RotationResult.Unknown());
    assertThat(service.rotate(thisDevice.value(), null)).isEqualTo(new RotationResult.Unknown());

    // the fresh family is untouched - no family revocation happened
    assertThat(service.rotate(fresh.value(), null)).isInstanceOf(RotationResult.Rotated.class);
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

    assertThat(service.rotate("kein-token", null)).isEqualTo(new RotationResult.Unknown());
    assertThat(service.rotate(issued.value(), null)).isEqualTo(new RotationResult.Unknown());
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

    assertThat(service.rotate(expiredAccount.value(), null))
        .isEqualTo(new RotationResult.Unknown());
    // refused, not revoked: an unlocked account rotates again
    row = fixtures.credentialsOf(user);
    row.setExpiresAt(null, Instant.now());
    fixtures.save(row);
    assertThat(service.rotate(expiredAccount.value(), null))
        .isInstanceOf(RotationResult.Rotated.class);

    IssuedRefreshToken regular = service.issue(user.user());
    IssuedRefreshToken administrative = service.issue(admin.user());
    fixtures.localProvider(false);
    try {
      assertThat(service.rotate(regular.value(), null)).isEqualTo(new RotationResult.Unknown());
      assertThat(service.rotate(administrative.value(), null))
          .isInstanceOf(RotationResult.Rotated.class);
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

  /**
   * Runs {@code rotation} twice concurrently while a connection of the test holds the row lock of
   * {@code lockedId}, and releases it only once both are waiting for a lock - the second queues
   * behind the first - so both have read the state before either can commit.
   */
  private List<RotationResult> raceWhileLocked(UUID lockedId, Callable<RotationResult> rotation)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Future<RotationResult>> futures;
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (PreparedStatement select =
          lock.prepareStatement("SELECT id FROM local_refresh_tokens WHERE id = ? FOR UPDATE")) {
        select.setObject(1, lockedId);
        select.executeQuery().close();
      }
      futures = List.of(pool.submit(rotation), pool.submit(rotation));
      awaitWaiters(lock, 2);
      lock.commit();
    }
    try {
      List<RotationResult> results = new ArrayList<>();
      for (Future<RotationResult> future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  private static void awaitWaiters(Connection lock, int expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    try (PreparedStatement waiting =
        lock.prepareStatement(
            "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                + " AND cardinality(pg_blocking_pids(pid)) > 0")) {
      while (System.nanoTime() < deadline) {
        try (ResultSet rows = waiting.executeQuery()) {
          rows.next();
          if (rows.getInt(1) >= expected) {
            return;
          }
        }
        Thread.sleep(10);
      }
    }
    throw new IllegalStateException("the concurrent rotations never waited for the row lock");
  }

  private List<UUID> activeTokensOfFamily(UUID familyId) {
    return jdbc.queryForList(
        "SELECT id FROM local_refresh_tokens WHERE family_id = ? AND revoked_at IS NULL",
        UUID.class,
        familyId);
  }

  private long auditedSessionRevocations() {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE event_type = 'LOCAL_SESSION_REVOKED' AND "
                + LocalAccountFixtures.NAMES_A_LOCAL_ACCOUNT,
            Long.class);
    return count == null ? 0 : count;
  }
}
