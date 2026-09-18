package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.LocalAccountActivity;
import io.opaa.auth.User;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * {@link LocalUserOverview}: the activity as a class (ADR-0033, Entscheidung 11) and the one rule
 * behind the review filter "länger als 90 Tage nicht genutzt".
 */
class LocalUserOverviewTest {

  private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z");

  @Test
  void theActivityIsAClassAroundTheNinetyDayBoundary() {
    assertThat(overview(null, false).activity()).isEqualTo(LocalAccountActivity.NEVER);
    assertThat(overview(NOW.minus(Duration.ofDays(89)), false).activity())
        .isEqualTo(LocalAccountActivity.ACTIVE);
    assertThat(overview(NOW.minus(Duration.ofDays(91)), false).activity())
        .isEqualTo(LocalAccountActivity.INACTIVE_90_DAYS);
  }

  /**
   * The one rule behind the review filter (#1641): both account lists read it here, and the
   * bootstrap account - exempt from the inactivity lock, so no candidate for a lock or a deletion -
   * is never a match, however long it has been resting.
   */
  @Test
  void anAccountCountsAsInactiveUnlessItIsActiveOrTheBootstrapAccount() {
    assertThat(overview(NOW.minus(Duration.ofDays(200)), false).countsAsInactive()).isTrue();
    assertThat(overview(null, false).countsAsInactive()).isTrue();
    assertThat(overview(NOW.minus(Duration.ofDays(1)), false).countsAsInactive()).isFalse();

    assertThat(overview(NOW.minus(Duration.ofDays(200)), true).countsAsInactive()).isFalse();
    assertThat(overview(null, true).countsAsInactive()).isFalse();
  }

  private static LocalUserOverview overview(Instant lastActivity, boolean bootstrap) {
    User user = User.localAccount("erika@stadt.example", "Erika Muster");
    user.setLastLoginAt(lastActivity);
    LocalCredentials credentials = new LocalCredentials(user.getId(), "Testkonto", NOW);
    credentials.setPasswordHash("{bcrypt}x", NOW);
    credentials.markEmailVerified(NOW);
    if (bootstrap) {
      credentials.markBootstrap();
    }
    return LocalUserOverview.of(user, credentials, NOW);
  }
}
