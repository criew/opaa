package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link LocalLockoutProperties} (ADR-0060, Entscheidung 17): the limits of the operation for the
 * lockout after failed sign-ins - by default the former fixed rule, at most five attempts and at
 * least fifteen minutes, so the administration can only tighten it. A still set retired {@code
 * OPAA_AUTH_LOCAL_LOCKOUT_DURATION} raises the shortest lockout, capped at the longest one; a limit
 * that would switch the protection off refuses the start, naming the variable.
 */
class LocalLockoutPropertiesTest {

  @Test
  void fillsInTheFormerFixedRuleAsDefaultLimits() {
    LocalLockoutProperties properties = new LocalLockoutProperties(null, null, null);

    assertThat(properties.maxAttempts()).isEqualTo(5);
    assertThat(properties.minDuration()).isEqualTo(Duration.ofMinutes(15));
    assertThat(properties.minDurationMinutes()).isEqualTo(15);
    assertThat(properties.legacyDurationCapped()).isFalse();
  }

  @Test
  void readsConfiguredLimits() {
    LocalLockoutProperties properties = new LocalLockoutProperties(3, Duration.ofHours(1), null);

    assertThat(properties.maxAttempts()).isEqualTo(3);
    assertThat(properties.minDurationMinutes()).isEqualTo(60);
  }

  /** The administration sets whole minutes: a shorter part of a minute rounds up, never down. */
  @Test
  void roundsTheShortestLockoutUpToWholeMinutes() {
    assertThat(new LocalLockoutProperties(null, Duration.ofSeconds(90), null).minDurationMinutes())
        .isEqualTo(2);
    assertThat(new LocalLockoutProperties(null, Duration.ofSeconds(30), null).minDurationMinutes())
        .isEqualTo(1);
  }

  /** A former fixed lockout of an hour stays the shortest one: the update loosens nothing. */
  @Test
  void aStillSetRetiredDurationRaisesTheShortestLockout() {
    LocalLockoutProperties properties =
        new LocalLockoutProperties(null, null, Duration.ofMinutes(60));

    assertThat(properties.minDurationMinutes()).isEqualTo(60);
    assertThat(properties.legacyDurationCapped()).isFalse();
  }

  @Test
  void aRetiredDurationShorterThanTheLimitChangesNothing() {
    LocalLockoutProperties properties =
        new LocalLockoutProperties(null, Duration.ofMinutes(30), Duration.ofMinutes(10));

    assertThat(properties.minDurationMinutes()).isEqualTo(30);
  }

  @Test
  void aRetiredDurationBeyondTheLongestLockoutIsCappedAndSaysSo() {
    LocalLockoutProperties properties = new LocalLockoutProperties(null, null, Duration.ofDays(7));

    assertThat(properties.minDurationMinutes())
        .isEqualTo(LocalAuthSettings.MAX_LOCKOUT_DURATION_MINUTES);
    assertThat(properties.legacyDurationCapped()).isTrue();
  }

  @Test
  void refusesALimitBelowOneAttemptNamingTheVariable() {
    assertThatThrownBy(() -> new LocalLockoutProperties(0, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(LocalLockoutProperties.MAX_ATTEMPTS_VARIABLE);
  }

  @Test
  void refusesANonPositiveShortestLockoutNamingTheVariable() {
    assertThatThrownBy(() -> new LocalLockoutProperties(null, Duration.ZERO, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(LocalLockoutProperties.MIN_DURATION_VARIABLE);
    assertThatThrownBy(() -> new LocalLockoutProperties(null, Duration.ofSeconds(-1), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(LocalLockoutProperties.MIN_DURATION_VARIABLE);
  }

  @Test
  void refusesANonPositiveRetiredDurationNamingTheVariable() {
    assertThatThrownBy(() -> new LocalLockoutProperties(null, null, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(LocalLockoutProperties.RETIRED_DURATION_VARIABLE);
  }

  @Test
  void refusesAShortestLockoutBeyondTheLongestTheAdministrationCanSet() {
    assertThatThrownBy(() -> new LocalLockoutProperties(null, Duration.ofHours(25), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(LocalLockoutProperties.MIN_DURATION_VARIABLE);
    assertThat(new LocalLockoutProperties(null, Duration.ofHours(24), null).minDurationMinutes())
        .isEqualTo(LocalAuthSettings.MAX_LOCKOUT_DURATION_MINUTES);
  }
}
