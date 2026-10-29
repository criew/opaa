package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** Both meters of the guard table are registered and read the process-wide table (ADR-0063). */
class GuardedNamesMetricsTest {

  @Test
  void theGuardTableReportsItsSizeAndItsRefusals() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new GuardedNamesMetrics(registry);

    assertThat(registry.get(GuardedNamesMetrics.GUARDED).gauge().value())
        .isEqualTo(ResolutionPins.process().guardedNames());
    assertThat(registry.get(GuardedNamesMetrics.REFUSED).functionCounter().count())
        .isEqualTo(ResolutionPins.process().refusedChecks());
  }
}
