package io.opaa.sourceaccess;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts what one run did against its source: requests sent (every wire attempt, retries included),
 * throttled answers ({@code 429}, {@code 503 SlowDown}) and the time spent waiting on them, and the
 * bytes downloaded. Thread-safe, never reset - one instance per run, read for the run's metrics. As
 * a {@link RateLimitListener} it counts every attempt and every wait the {@link
 * RedirectFollowingFetcher} performs; a caller that bounds its run charges through {@link
 * #recordRequestWithin}.
 */
public final class SourceRequestMeter implements RateLimitListener {

  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicInteger throttles = new AtomicInteger();
  private final AtomicLong throttledMillis = new AtomicLong();
  private final AtomicLong bytesDownloaded = new AtomicLong();

  public void recordRequest() {
    requests.incrementAndGet();
  }

  /**
   * Counts one request unless {@code budget} (positive) is already spent - check and count in one
   * atomic step, so concurrent callers cannot overshoot the budget. Returns whether it was counted.
   */
  public boolean recordRequestWithin(int budget) {
    int before = requests.getAndUpdate(n -> budget > 0 && n >= budget ? n : n + 1);
    return budget <= 0 || before < budget;
  }

  /** A throttled answer whose wait is not known yet ({@link #recordThrottleWait} follows). */
  public void recordThrottle() {
    throttles.incrementAndGet();
  }

  /** A throttled answer waited out for {@code waited}. */
  public void recordThrottle(Duration waited) {
    recordThrottle();
    recordThrottleWait(waited);
  }

  public void recordThrottleWait(Duration waited) {
    throttledMillis.addAndGet(Math.max(0, waited.toMillis()));
  }

  /**
   * Counts a throttled answer and its wait unless the total waiting time would then exceed {@code
   * cap} - check and count in one atomic step, so concurrent callers cannot overshoot the cap.
   * Returns whether it was counted.
   */
  public boolean recordThrottleWithin(Duration waited, Duration cap) {
    long millis = Math.max(0, waited.toMillis());
    long capMillis = cap.toMillis();
    long before = throttledMillis.getAndUpdate(n -> n + millis > capMillis ? n : n + millis);
    if (before + millis > capMillis) {
      return false;
    }
    throttles.incrementAndGet();
    return true;
  }

  public void recordBytes(long bytes) {
    bytesDownloaded.addAndGet(bytes);
  }

  @Override
  public void throttled(int statusCode, Duration wait) {
    recordThrottle(wait);
  }

  @Override
  public void sending() {
    recordRequest();
  }

  public int requests() {
    return requests.get();
  }

  public int throttles() {
    return throttles.get();
  }

  public Duration throttledTime() {
    return Duration.ofMillis(throttledMillis.get());
  }

  public long bytesDownloaded() {
    return bytesDownloaded.get();
  }
}
