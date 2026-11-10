package io.opaa.s3;

import io.opaa.observability.OutboundMetrics;
import io.opaa.observability.OutboundOutcome;
import io.opaa.observability.OutboundPathClass;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.core.metrics.CoreMetric;
import software.amazon.awssdk.http.HttpMetric;
import software.amazon.awssdk.metrics.MetricCollection;
import software.amazon.awssdk.metrics.MetricPublisher;

/**
 * Hands the SDK's own metrics of every attempt to {@link OutboundMetrics} (ADR-0071, Entscheidung
 * 8): one {@code opaa.outbound.requests} per attempt, a retry included, with the outcome from the
 * answer's status or the SDK's error type. The SDK publishes a synchronous call on the calling
 * thread, which decides the path class. The SDK names no TLS failure apart, so a failed handshake
 * counts as {@code io_error}; an attempt without service time counts with the time of the whole
 * call. An attempt the {@link S3RequestGuard} refused (target, budget) never left the process and
 * does not count; the guard marks it on the calling thread, and since a refusal is not retried, it
 * is always the call's last attempt.
 */
final class S3OutboundMetrics implements MetricPublisher {

  private static final String ATTEMPT = "ApiCallAttempt";

  /** Whether the guard refused the last attempt made on this thread; reset by every attempt. */
  private static final ThreadLocal<Boolean> REFUSED = new ThreadLocal<>();

  private final OutboundMetrics metrics;

  S3OutboundMetrics(OutboundMetrics metrics) {
    this.metrics = metrics;
  }

  @Override
  public void publish(MetricCollection call) {
    OutboundPathClass path = OutboundPathClass.ofCurrentThread();
    Duration callDuration = first(call.metricValues(CoreMetric.API_CALL_DURATION), Duration.ZERO);
    List<MetricCollection> attempts = new ArrayList<>(call.childrenWithName(ATTEMPT).toList());
    if (Boolean.TRUE.equals(REFUSED.get()) && !attempts.isEmpty()) {
      attempts.removeLast();
    }
    REFUSED.remove();
    attempts.forEach(
        attempt -> {
          Duration duration =
              first(attempt.metricValues(CoreMetric.SERVICE_CALL_DURATION), callDuration);
          OutboundOutcome outcome = outcome(attempt);
          if (outcome != null) {
            metrics.record(path, outcome, duration.toNanos());
          }
        });
  }

  /** Called by the guard before each attempt with whether it refuses that attempt. */
  static void attempt(boolean refused) {
    if (refused) {
      REFUSED.set(Boolean.TRUE);
    } else {
      REFUSED.remove();
    }
  }

  /** The class of one attempt, {@code null} for one that left no trace of an answer or error. */
  static OutboundOutcome outcome(MetricCollection attempt) {
    String errorType = first(attempt.metricValues(CoreMetric.ERROR_TYPE), null);
    if ("Throttling".equals(errorType)) {
      return OutboundOutcome.THROTTLED;
    }
    if ("ConfiguredTimeout".equals(errorType)) {
      return OutboundOutcome.TIMEOUT;
    }
    Integer status = first(attempt.metricValues(HttpMetric.HTTP_STATUS_CODE), null);
    if (status != null) {
      return OutboundOutcome.ofStatus(status, null);
    }
    return errorType == null ? null : OutboundOutcome.IO_ERROR;
  }

  private static <T> T first(List<T> values, T fallback) {
    return values.isEmpty() ? fallback : values.getFirst();
  }

  @Override
  public void close() {}
}
