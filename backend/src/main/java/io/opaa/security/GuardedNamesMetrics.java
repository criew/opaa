package io.opaa.security;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * How full this instance's table of names guarded against DNS rebinding is, and how many checks a
 * full table refused, of stored targets and before a connection (ADR-0063). Read from the
 * process-wide {@link ResolutionPins}; no name, address or account is ever a tag.
 */
@Component
class GuardedNamesMetrics {

  static final String GUARDED = "opaa.target_validation.guarded_names";
  static final String REFUSED = "opaa.target_validation.refused_checks";
  static final String REFUSED_CONNECTION = "opaa.target_validation.refused_connection_checks";

  GuardedNamesMetrics(MeterRegistry registry) {
    ResolutionPins pins = ResolutionPins.process();
    Gauge.builder(GUARDED, pins, ResolutionPins::guardedNames)
        .description("Host names this instance guards against DNS rebinding until restart")
        .register(registry);
    FunctionCounter.builder(REFUSED, pins, ResolutionPins::refusedChecks)
        .description("Checks of a new host name refused because the guard table is full")
        .register(registry);
    FunctionCounter.builder(REFUSED_CONNECTION, pins, ResolutionPins::refusedConnectionChecks)
        .description("Checks before a connection refused because every pin is still in force")
        .register(registry);
  }
}
