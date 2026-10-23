package io.opaa.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.function.IntSupplier;

/**
 * Metrics of the self-disclosure without any reference to a person: started, completed, aborted by
 * cause, refused by reason, duration, size and the exports running on this instance. Tags carry
 * only these fixed vocabularies, never an account, a token or an address.
 */
public class SelfDisclosureMetrics {

  public static final String EXPORTS = "opaa.self_disclosure.exports";
  public static final String REFUSED = "opaa.self_disclosure.refused";
  public static final String DURATION = "opaa.self_disclosure.duration";
  public static final String SIZE = "opaa.self_disclosure.size";
  public static final String RUNNING = "opaa.self_disclosure.running";

  /** Why a request for an export was turned away before anything was read. */
  public enum Refusal {
    DAILY_LIMIT,
    STARTS_EXHAUSTED,
    RUNNING_EXPORT,
    INSTANCE_LIMIT,
    DISABLED,
    RECENT_LOGIN_REQUIRED
  }

  private final MeterRegistry registry;
  private final Counter started;
  private final Counter completed;
  private final Timer duration;
  private final DistributionSummary size;

  public SelfDisclosureMetrics(MeterRegistry registry, IntSupplier running) {
    this.registry = registry;
    this.started = outcome("started");
    this.completed = outcome("completed");
    this.duration =
        Timer.builder(DURATION)
            .description("Duration of self-disclosure exports, completed or aborted")
            .register(registry);
    this.size =
        DistributionSummary.builder(SIZE)
            .baseUnit("bytes")
            .description("Bytes delivered per self-disclosure export")
            .register(registry);
    Gauge.builder(RUNNING, running::getAsInt)
        .description("Self-disclosure exports streaming on this instance")
        .register(registry);
  }

  private Counter outcome(String result) {
    return Counter.builder(EXPORTS)
        .tag("result", result)
        .tag("cause", "none")
        .description("Self-disclosure exports by result")
        .register(registry);
  }

  public void started() {
    started.increment();
  }

  public void completed(Duration took, long bytes) {
    completed.increment();
    duration.record(took);
    size.record(bytes);
  }

  /** {@code cause} is one of the fixed abort causes of the audit event. */
  public void aborted(String cause, Duration took, long bytes) {
    Counter.builder(EXPORTS)
        .tag("result", "aborted")
        .tag("cause", cause)
        .description("Self-disclosure exports by result")
        .register(registry)
        .increment();
    duration.record(took);
    size.record(bytes);
  }

  public void refused(Refusal reason) {
    Counter.builder(REFUSED)
        .tag("reason", reason.name())
        .description("Self-disclosure requests refused before anything was read")
        .register(registry)
        .increment();
  }
}
