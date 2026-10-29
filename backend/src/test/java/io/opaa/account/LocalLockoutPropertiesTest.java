package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link LocalLockoutProperties} (ADR-0060, Entscheidung 17): the limits of the operation for the
 * lockout after failed sign-ins - by default at most ten attempts and at least five minutes. A
 * limit that would switch the protection off or lie outside what the administration can set refuses
 * the start, naming the environment variable.
 */
class LocalLockoutPropertiesTest {

  @Test
  void fillsInTheDefaultLimits() {
    LocalLockoutProperties properties = new LocalLockoutProperties(null, null);

    assertThat(properties.maxAttempts()).isEqualTo(10);
    assertThat(properties.minDuration()).isEqualTo(Duration.ofMinutes(5));
    assertThat(properties.minDurationMinutes()).isEqualTo(5);
  }

  @Test
  void readsConfiguredLimits() {
    LocalLockoutProperties properties = new LocalLockoutProperties(3, Duration.ofHours(1));

    assertThat(properties.maxAttempts()).isEqualTo(3);
    assertThat(properties.minDurationMinutes()).isEqualTo(60);
  }

  /** The administration sets whole minutes: a shorter part of a minute rounds up, never down. */
  @Test
  void roundsTheShortestLockoutUpToWholeMinutes() {
    assertThat(new LocalLockoutProperties(null, Duration.ofSeconds(90)).minDurationMinutes())
        .isEqualTo(2);
    assertThat(new LocalLockoutProperties(null, Duration.ofSeconds(30)).minDurationMinutes())
        .isEqualTo(1);
  }

  @Test
  void refusesALimitBelowOneAttemptNamingTheVariable() {
    assertThatThrownBy(() -> new LocalLockoutProperties(0, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(LocalLockoutProperties.MAX_ATTEMPTS_VARIABLE);
  }

  @Test
  void refusesANonPositiveShortestLockoutNamingTheVariable() {
    assertThatThrownBy(() -> new LocalLockoutProperties(null, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(LocalLockoutProperties.MIN_DURATION_VARIABLE);
    assertThatThrownBy(() -> new LocalLockoutProperties(null, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(LocalLockoutProperties.MIN_DURATION_VARIABLE);
  }

  @Test
  void refusesAShortestLockoutBeyondTheLongestTheAdministrationCanSet() {
    assertThatThrownBy(() -> new LocalLockoutProperties(null, Duration.ofHours(25)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(LocalLockoutProperties.MIN_DURATION_VARIABLE);
    assertThat(new LocalLockoutProperties(null, Duration.ofHours(24)).minDurationMinutes())
        .isEqualTo(LocalAuthSettings.MAX_LOCKOUT_DURATION_MINUTES);
  }
}
