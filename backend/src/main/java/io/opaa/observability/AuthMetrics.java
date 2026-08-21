package io.opaa.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Encapsulates all Micrometer metrics related to authentication and user provisioning.
 *
 * <p>#307 review, finding 3: a repeatedly failing personal-space provisioning (see {@code
 * UserService#ensurePersonalSpace}) must be genuinely visible, not merely logged with a hand-rolled
 * counter that resets on every restart and lives outside every dashboard/alerting rule the existing
 * {@link IndexingMetrics}/{@link QueryMetrics} counters already feed - this is that same, already
 * standing Micrometer infrastructure, not new infrastructure of its own.
 */
public class AuthMetrics {

  private final Counter personalSpaceProvisioningFailedCounter;

  public AuthMetrics(MeterRegistry meterRegistry) {
    this.personalSpaceProvisioningFailedCounter =
        Counter.builder("opaa.auth.personal_space_provisioning")
            .tag("result", "failed")
            .description("Failed personal space provisioning attempts")
            .register(meterRegistry);
  }

  public void recordPersonalSpaceProvisioningFailed() {
    personalSpaceProvisioningFailedCounter.increment();
  }

  /** Total failed attempts since startup - logged alongside every failure, see the call site. */
  public double personalSpaceProvisioningFailedCount() {
    return personalSpaceProvisioningFailedCounter.count();
  }
}
