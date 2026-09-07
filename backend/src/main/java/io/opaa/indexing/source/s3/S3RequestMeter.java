package io.opaa.indexing.source.s3;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts what one store did against its endpoint: requests sent (every wire attempt, retries
 * included), throttles ({@code 503}/{@code 429}) answered with a retry and the time spent waiting
 * on them, and the bytes downloaded. Read by runs for their metrics and by the request budget;
 * never reset.
 */
public final class S3RequestMeter {

  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicInteger throttles = new AtomicInteger();
  private final AtomicLong throttledMillis = new AtomicLong();
  private final AtomicLong bytesDownloaded = new AtomicLong();

  void recordRequest() {
    requests.incrementAndGet();
  }

  /**
   * Counts one request unless {@code budget} (positive) is already spent - check and count in one
   * atomic step, so concurrent callers cannot overshoot the budget. Returns whether it was counted.
   */
  boolean recordRequestWithin(int budget) {
    int before = requests.getAndUpdate(n -> budget > 0 && n >= budget ? n : n + 1);
    return budget <= 0 || before < budget;
  }

  void recordThrottle() {
    throttles.incrementAndGet();
  }

  void recordThrottleWait(Duration waited) {
    throttledMillis.addAndGet(Math.max(0, waited.toMillis()));
  }

  void recordBytes(long bytes) {
    bytesDownloaded.addAndGet(bytes);
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
