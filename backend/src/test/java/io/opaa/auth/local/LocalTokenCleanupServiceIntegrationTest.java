package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.security.LocalAuthKeyService;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The daily cleanup of the three token tables (ADR-0033, Entscheidung 7): a row goes at the latest
 * {@link LocalTokenCleanupService#RETENTION} after its expiry, its family's end, its revocation or
 * its consumption - and not a day earlier, so a replayed token is still recognised as a replay for
 * the whole window.
 */
@OpaaIntegrationTest
class LocalTokenCleanupServiceIntegrationTest {

  @Autowired private LocalTokenCleanupService service;
  @Autowired private LocalRefreshTokenRepository refreshTokens;
  @Autowired private LocalRevokedTokenRepository revokedTokens;
  @Autowired private LocalActionTokenRepository actionTokens;
  @Autowired private io.opaa.test.LocalAccountFixturesFactory fixturesFactory;

  private LocalAccountFixtures fixtures;
  private LocalAccount user;
  private final Instant now = Instant.now();

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    actionTokens.deleteAll();
    user = fixtures.activeUser("konto-" + UUID.randomUUID() + "@stadt.example");
  }

  @AfterEach
  void tearDown() {
    actionTokens.deleteAll();
    fixtures.cleanUp();
  }

  @Test
  void deletesRowsSevenDaysAfterExpiryOrRevocationAndKeepsYoungerOnes() {
    Duration pastRetention = LocalTokenCleanupService.RETENTION.plus(Duration.ofHours(1));
    Duration withinRetention = LocalTokenCleanupService.RETENTION.minus(Duration.ofHours(1));
    // refresh: one long expired, one revoked long ago, one revoked recently, one active
    refreshTokens.save(
        refreshToken("expired", now.minus(pastRetention), now.plus(Duration.ofDays(30))));
    LocalRefreshToken revokedLongAgo =
        refreshToken("revoked-old", now.plus(Duration.ofDays(7)), now.plus(Duration.ofDays(30)));
    revokedLongAgo.revoke(RevocationReason.LOGOUT, now.minus(pastRetention));
    refreshTokens.save(revokedLongAgo);
    LocalRefreshToken revokedRecently =
        refreshToken("revoked-new", now.plus(Duration.ofDays(7)), now.plus(Duration.ofDays(30)));
    revokedRecently.revoke(RevocationReason.LOGOUT, now.minus(withinRetention));
    refreshTokens.save(revokedRecently);
    refreshTokens.save(
        refreshToken("active", now.plus(Duration.ofDays(7)), now.plus(Duration.ofDays(30))));
    // denylist: one whose token expired long ago, one still within the window
    revokedTokens.save(
        new LocalRevokedToken(
            LocalAuthKeyService.jtiHash("old"), user.id(), now.minus(pastRetention), now));
    revokedTokens.save(
        new LocalRevokedToken(
            LocalAuthKeyService.jtiHash("new"), user.id(), now.minus(withinRetention), now));
    // action tokens: one consumed long ago, one open
    LocalActionToken consumed =
        new LocalActionToken(
            user.id(),
            ActionTokenPurpose.RESET_PASSWORD,
            "consumed",
            now.minus(Duration.ofDays(30)),
            now.plus(Duration.ofDays(30)));
    actionTokens.save(consumed);
    actionTokens.markConsumed(consumed.getId(), now.minus(pastRetention));
    actionTokens.save(
        new LocalActionToken(
            user.id(), ActionTokenPurpose.SET_PASSWORD, "open", now, now.plus(Duration.ofDays(3))));

    LocalTokenCleanupService.Result result = service.runOnce();

    assertThat(result.refreshTokens()).isEqualTo(2);
    assertThat(result.revokedTokens()).isEqualTo(1);
    assertThat(result.actionTokens()).isEqualTo(1);
    assertThat(refreshTokens.findAll())
        .extracting(LocalRefreshToken::getTokenLookupHash)
        .containsExactlyInAnyOrder("revoked-new", "active");
    assertThat(revokedTokens.existsById(LocalAuthKeyService.jtiHash("new"))).isTrue();
    assertThat(revokedTokens.existsById(LocalAuthKeyService.jtiHash("old"))).isFalse();
    assertThat(actionTokens.findAll())
        .extracting(LocalActionToken::getTokenHash)
        .containsExactly("open");
  }

  /** Issued long ago, so every expiry the tests choose satisfies the table's ordering CHECK. */
  private LocalRefreshToken refreshToken(String hash, Instant expiresAt, Instant familyExpiresAt) {
    return new LocalRefreshToken(
        UUID.randomUUID(),
        user.id(),
        hash,
        now.minus(Duration.ofDays(60)),
        expiresAt,
        familyExpiresAt);
  }
}
