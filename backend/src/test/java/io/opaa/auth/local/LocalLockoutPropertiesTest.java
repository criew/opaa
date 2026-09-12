package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link LocalLockoutProperties} (ADR-0033, Entscheidung 9): five attempts and a fixed quarter of
 * an hour by default; a threshold below one or a non-positive duration refuses the start, naming
 * the environment variable.
 */
class LocalLockoutPropertiesTest {

  @Test
  void fillsInTheAdrDefaults() {
    LocalLockoutProperties properties = new LocalLockoutProperties(null, null);

    assertThat(properties.maxAttempts()).isEqualTo(5);
    assertThat(properties.duration()).isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  void readsConfiguredValues() {
    LocalLockoutProperties properties = new LocalLockoutProperties(3, Duration.ofHours(1));

    assertThat(properties.maxAttempts()).isEqualTo(3);
    assertThat(properties.duration()).isEqualTo(Duration.ofHours(1));
  }

  @Test
  void refusesAThresholdBelowOneNamingTheVariable() {
    assertThatThrownBy(() -> new LocalLockoutProperties(0, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(LocalLockoutProperties.MAX_ATTEMPTS_VARIABLE);
  }

  @Test
  void refusesANonPositiveDurationNamingTheVariable() {
    assertThatThrownBy(() -> new LocalLockoutProperties(null, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(LocalLockoutProperties.DURATION_VARIABLE);
    assertThatThrownBy(() -> new LocalLockoutProperties(null, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(LocalLockoutProperties.DURATION_VARIABLE);
  }
}
