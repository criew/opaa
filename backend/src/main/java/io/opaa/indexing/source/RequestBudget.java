package io.opaa.indexing.source;

import io.opaa.sourceaccess.RateLimitListener;
import io.opaa.sourceaccess.SourceRequestMeter;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.time.Duration;
import java.util.Objects;

/**
 * Bounds one run's requests on its {@link SourceRequestMeter}: at most {@code requestBudget}
 * requests ({@code 0}: unbounded) and at most {@code maxThrottleWait} of waiting on throttled
 * answers in total ({@code null} or zero: unbounded). Every request is charged before it leaves
 * ({@link #charge}); as the {@link RateLimitListener} of a fetch it charges every attempt and
 * refuses a wait that would cross the cap before that wait is slept - each with a {@link
 * RequestBudgetExhaustedException}.
 */
public final class RequestBudget implements RateLimitListener {

  private final SourceRequestMeter meter;
  private final int requestBudget;
  private final Duration maxThrottleWait;

  public RequestBudget(SourceRequestMeter meter, int requestBudget, Duration maxThrottleWait) {
    this.meter = Objects.requireNonNull(meter, "meter");
    this.requestBudget = Math.max(0, requestBudget);
    this.maxThrottleWait =
        maxThrottleWait == null || maxThrottleWait.isNegative() || maxThrottleWait.isZero()
            ? null
            : maxThrottleWait;
  }

  /** A fresh meter under the run bounds of {@code policy}. */
  public static RequestBudget forRun(SourceRequestPolicy policy) {
    return new RequestBudget(
        new SourceRequestMeter(), policy.requestBudgetPerRun(), policy.maxRateLimitWaitPerRun());
  }

  /** A fresh meter without bounds - counting only. */
  public static RequestBudget unbounded() {
    return new RequestBudget(new SourceRequestMeter(), 0, null);
  }

  public SourceRequestMeter meter() {
    return meter;
  }

  public int requestBudget() {
    return requestBudget;
  }

  /** Counts one request; refuses it when the budget is spent, leaving the count as it is. */
  public void charge() {
    if (!meter.recordRequestWithin(requestBudget)) {
      throw RequestBudgetExhaustedException.requests(requestBudget);
    }
  }

  @Override
  public void throttled(int statusCode, Duration wait) {
    if (maxThrottleWait != null
        && meter.throttledTime().plus(wait).compareTo(maxThrottleWait) > 0) {
      throw RequestBudgetExhaustedException.throttleWait(maxThrottleWait);
    }
    meter.recordThrottle(wait);
  }

  @Override
  public void sending() {
    charge();
  }
}
