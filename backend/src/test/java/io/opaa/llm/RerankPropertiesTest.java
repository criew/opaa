package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The binding rules of the rerank role's load limits. */
class RerankPropertiesTest {

  @Test
  void aMissingOrNonPositiveTimeoutFallsBackToTheCpuTunedDefault() {
    Duration expected = Duration.ofSeconds(RerankProperties.DEFAULT_TIMEOUT_SECONDS);
    assertThat(new RerankProperties(null, null).timeout()).isEqualTo(expected);
    assertThat(new RerankProperties(Duration.ZERO, null).timeout()).isEqualTo(expected);
    assertThat(new RerankProperties(Duration.ofSeconds(-5), null).timeout()).isEqualTo(expected);
  }

  @Test
  void aMissingProbeIntervalFallsBackToTheDefault() {
    assertThat(new RerankProperties(null, null).probeInterval())
        .isEqualTo(Duration.ofSeconds(RerankProperties.DEFAULT_PROBE_INTERVAL_SECONDS));
    assertThat(new RerankProperties(null, Duration.ofHours(24)).probeInterval())
        .isEqualTo(Duration.ofHours(24));
  }

  /** A probe interval below the minimum is a configuration error and fails the start. */
  @Test
  void aProbeIntervalBelowTheMinimumIsRejected() {
    for (Duration invalid :
        new Duration[] {Duration.ZERO, Duration.ofSeconds(-5), Duration.ofSeconds(9)}) {
      assertThatThrownBy(() -> new RerankProperties(null, invalid))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("opaa.rerank.probe-interval");
    }
    assertThat(new RerankProperties(null, Duration.ofSeconds(10)).probeInterval())
        .isEqualTo(Duration.ofSeconds(10));
  }
}
