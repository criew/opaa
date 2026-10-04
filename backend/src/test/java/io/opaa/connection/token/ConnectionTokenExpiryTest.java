package io.opaa.connection.token;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.connection.token.ConnectionToken.Ciphered;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * When the end a provider names is warned of: a consent's end once; a lifetime shorter than the
 * warning never; a sliding end that every renewal moves within the warning not daily.
 */
class ConnectionTokenExpiryTest {

  private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");

  @Test
  void aConsentEndingLaterIsWarnedOfOnceWhenItComesNear() {
    ConnectionToken token = grant(NOW.plus(Duration.ofDays(60)));

    assertThat(token.getExpiryWarnedAt()).isNull();
  }

  @Test
  void aConsentWhoseFirstLifetimeIsShorterThanTheWarningIsNeverWarnedOf() {
    ConnectionToken token = grant(NOW.plus(Duration.ofMinutes(30)));

    assertThat(token.getExpiryWarnedAt()).isEqualTo(NOW);
  }

  /** Regression guard: a sliding idle end moved by every renewal must not reopen the warning. */
  @Test
  void aRotationKeepingTheEndWithinTheWarningKeepsTheMarker() {
    ConnectionToken token = grant(NOW.plus(Duration.ofDays(20)));
    Instant warned = NOW.plus(Duration.ofDays(7));
    token.expiryWarned(warned);

    Instant later = NOW.plus(Duration.ofDays(8));
    token.renewed(
        "a2", later.plus(Duration.ofHours(1)), "r2", later.plus(Duration.ofDays(5)), later);

    assertThat(token.getExpiryWarnedAt()).isEqualTo(warned);
  }

  @Test
  void aRotationMovingTheEndOutOfTheWarningIsWarnedOfAnew() {
    ConnectionToken token = grant(NOW.plus(Duration.ofDays(20)));
    token.expiryWarned(NOW.plus(Duration.ofDays(7)));

    Instant later = NOW.plus(Duration.ofDays(8));
    token.renewed(
        "a2", later.plus(Duration.ofHours(1)), "r2", later.plus(Duration.ofDays(30)), later);

    assertThat(token.getExpiryWarnedAt()).isNull();
  }

  @Test
  void aReconnectionIsANewConsent() {
    ConnectionToken token = grant(NOW.plus(Duration.ofDays(20)));
    token.expiryWarned(NOW.plus(Duration.ofDays(7)));

    Instant later = NOW.plus(Duration.ofDays(8));
    token.replaceGrant(
        new Ciphered("r3", "a3", later.plus(Duration.ofHours(1)), later.plus(Duration.ofDays(90))),
        "target",
        later);

    assertThat(token.getExpiryWarnedAt()).isNull();
  }

  private static ConnectionToken grant(Instant end) {
    return ConnectionToken.ofAccountGrant(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new Ciphered("r1", "a1", NOW.plus(Duration.ofHours(1)), end),
        "target",
        NOW);
  }
}
