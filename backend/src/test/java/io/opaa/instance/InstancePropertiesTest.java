package io.opaa.instance;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The deadline of the register covers at least four heartbeats (ADR-0053, Entscheidung 3). */
class InstancePropertiesTest {

  @Test
  void acceptsTheDeliveredValues() {
    assertThatCode(() -> new InstanceProperties(Duration.ofSeconds(15), Duration.ofSeconds(120)))
        .doesNotThrowAnyException();
  }

  @Test
  void acceptsExactlyFourHeartbeats() {
    assertThatCode(() -> new InstanceProperties(Duration.ofSeconds(15), Duration.ofSeconds(60)))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesADeadlineShorterThanFourHeartbeats() {
    assertThatThrownBy(() -> new InstanceProperties(Duration.ofSeconds(15), Duration.ofSeconds(59)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("opaa.instance.dead-after");
  }

  @Test
  void refusesANonPositiveInterval() {
    assertThatThrownBy(() -> new InstanceProperties(Duration.ZERO, Duration.ofSeconds(120)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("opaa.instance.heartbeat-interval");
  }
}
