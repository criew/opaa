package io.opaa.indexing.source.confluence;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts what one client did against its instance: requests sent, {@code 429} throttles honoured
 * and the total time spent waiting on them. Read by runs for their metrics; never reset.
 */
public final class ConfluenceRequestMeter {

  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicInteger throttles = new AtomicInteger();
  private final AtomicLong throttledMillis = new AtomicLong();

  void recordRequest() {
    requests.incrementAndGet();
  }

  void recordThrottle(Duration waited) {
    throttles.incrementAndGet();
    throttledMillis.addAndGet(waited.toMillis());
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
}
