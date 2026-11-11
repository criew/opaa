package io.opaa.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Rate, errors and duration of the outbound calls to one {@link OutboundTarget} (ADR-0071,
 * Entscheidung 8): {@code opaa.outbound.requests} with {@code target}, {@code kind}, {@code
 * outcome} and {@code path_class}, and {@code opaa.outbound.throttled} for every throttled answer.
 * Neither host nor address is a tag; {@code kind} is a connector type or directory provider from
 * code, {@value #NO_KIND} for every other target.
 */
public final class OutboundMetrics {

  public static final String REQUESTS = "opaa.outbound.requests";

  public static final String THROTTLED = "opaa.outbound.throttled";

  /** The {@code kind} of a target without connector type or provider. */
  public static final String NO_KIND = "none";

  private static final Pattern KIND = Pattern.compile("[a-z][a-z0-9_]{0,19}");

  private final MeterRegistry registry;
  private final OutboundTarget target;
  private final String kind;

  /**
   * @param kind the connector type or directory provider, lower case; {@code null} for a target
   *     without one
   */
  public OutboundMetrics(MeterRegistry registry, OutboundTarget target, String kind) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.target = Objects.requireNonNull(target, "target");
    if (target.hasKind() != (kind != null)) {
      throw new IllegalArgumentException(
          "target " + target.tagValue() + (target.hasKind() ? " needs" : " takes no") + " kind");
    }
    if (kind != null && !KIND.matcher(kind).matches()) {
      throw new IllegalArgumentException("no kind: " + kind);
    }
    this.kind = kind == null ? NO_KIND : kind;
  }

  /**
   * The metrics of a client built outside the application context, on the global registry Spring
   * Boot binds the application's registry to ({@code management.metrics.use-global-registry}).
   */
  public static OutboundMetrics global(OutboundTarget target, String kind) {
    return new OutboundMetrics(Metrics.globalRegistry, target, kind);
  }

  public OutboundTarget target() {
    return target;
  }

  /** Records one call that took {@code nanos} and ended with {@code outcome}. */
  public void record(OutboundPathClass pathClass, OutboundOutcome outcome, long nanos) {
    Timer.builder(REQUESTS)
        .tag("target", target.tagValue())
        .tag("kind", kind)
        .tag("outcome", outcome.tagValue())
        .tag("path_class", pathClass.tagValue())
        .description("Outbound calls to rerank, transcription, OCR, connectors and other services")
        .register(registry)
        .record(Math.max(0, nanos), TimeUnit.NANOSECONDS);
    if (outcome == OutboundOutcome.THROTTLED) {
      Counter.builder(THROTTLED)
          .tag("target", target.tagValue())
          .tag("path_class", pathClass.tagValue())
          .description("Outbound calls answered with 429 or 503 with Retry-After")
          .register(registry)
          .increment();
    }
  }
}
