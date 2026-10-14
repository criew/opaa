package io.opaa.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** {@link PropertyDefaults}: zero means the default, a negative value is replaced or refused. */
class PropertyDefaultsTest {

  @Test
  void zeroAndNegativeNumbersFallBackToTheDefault() {
    assertThat(PropertyDefaults.positiveOr(0, 7)).isEqualTo(7);
    assertThat(PropertyDefaults.positiveOr(-1, 7)).isEqualTo(7);
    assertThat(PropertyDefaults.positiveOr(3, 7)).isEqualTo(3);
    assertThat(PropertyDefaults.positiveOr(0L, 7L)).isEqualTo(7L);
    assertThat(PropertyDefaults.positiveOr(-1L, 7L)).isEqualTo(7L);
    assertThat(PropertyDefaults.positiveOr(3L, 7L)).isEqualTo(3L);
  }

  @Test
  void absentZeroAndNegativeDurationsFallBackToTheDefault() {
    Duration fallback = Duration.ofSeconds(30);

    assertThat(PropertyDefaults.positiveOr((Duration) null, fallback)).isEqualTo(fallback);
    assertThat(PropertyDefaults.positiveOr(Duration.ZERO, fallback)).isEqualTo(fallback);
    assertThat(PropertyDefaults.positiveOr(Duration.ofSeconds(-1), fallback)).isEqualTo(fallback);
    assertThat(PropertyDefaults.positiveOr(Duration.ofSeconds(5), fallback))
        .isEqualTo(Duration.ofSeconds(5));
    assertThat(PropertyDefaults.positiveOr(Duration.ofNanos(1), fallback))
        .isEqualTo(Duration.ofNanos(1));
  }

  @Test
  void notNegativeOrTakesZeroAsTheDefaultAndRefusesANegativeValue() {
    assertThat(PropertyDefaults.notNegativeOr("pageSize", 0, 100)).isEqualTo(100);
    assertThat(PropertyDefaults.notNegativeOr("pageSize", 1, 100)).isEqualTo(1);
    assertThat(PropertyDefaults.notNegativeOr("maxBytes", 0L, 100L)).isEqualTo(100L);
    assertThatThrownBy(() -> PropertyDefaults.notNegativeOr("pageSize", -1, 100))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("pageSize must not be negative, got -1");
    assertThatThrownBy(() -> PropertyDefaults.notNegativeOr("maxBytes", -1L, 100L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("maxBytes must not be negative, got -1");
    assertThatThrownBy(() -> PropertyDefaults.requireNotNegative("budget", -2))
        .hasMessage("budget must not be negative, got -2");
    assertThatCode(() -> PropertyDefaults.requireNotNegative("budget", 0))
        .doesNotThrowAnyException();
  }
}
