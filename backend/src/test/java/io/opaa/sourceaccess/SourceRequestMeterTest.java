package io.opaa.sourceaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** One meter per run: what it counts, how it bounds, and what it hears from the fetcher. */
class SourceRequestMeterTest {

  private final SourceRequestMeter meter = new SourceRequestMeter();

  @Test
  void startsAtZeroAndCountsRequestsThrottlesWaitsAndBytes() {
    assertThat(meter.requests()).isZero();
    assertThat(meter.throttles()).isZero();
    assertThat(meter.throttledTime()).isZero();
    assertThat(meter.bytesDownloaded()).isZero();

    meter.recordRequest();
    meter.recordRequest();
    meter.recordThrottle(Duration.ofSeconds(2));
    meter.recordThrottle();
    meter.recordThrottleWait(Duration.ofMillis(500));
    meter.recordBytes(1024);
    meter.recordBytes(1);

    assertThat(meter.requests()).isEqualTo(2);
    assertThat(meter.throttles()).isEqualTo(2);
    assertThat(meter.throttledTime()).isEqualTo(Duration.ofMillis(2500));
    assertThat(meter.bytesDownloaded()).isEqualTo(1025);
  }

  @Test
  void aNegativeWaitCountsAsNothing() {
    meter.recordThrottleWait(Duration.ofSeconds(-3));

    assertThat(meter.throttledTime()).isZero();
  }

  @Test
  void recordRequestWithinCountsUpToTheBudgetAndRefusesTheRest() {
    assertThat(meter.recordRequestWithin(2)).isTrue();
    assertThat(meter.recordRequestWithin(2)).isTrue();
    assertThat(meter.recordRequestWithin(2)).as("the third is refused, not counted").isFalse();
    assertThat(meter.requests()).isEqualTo(2);
  }

  @Test
  void aBudgetOfZeroIsNoBudget() {
    for (int i = 0; i < 10; i++) {
      assertThat(meter.recordRequestWithin(0)).isTrue();
    }
    assertThat(meter.requests()).isEqualTo(10);
  }

  @Test
  void concurrentCallersNeverOvershootTheBudget() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(8);
    AtomicInteger admitted = new AtomicInteger();
    try {
      Future<?>[] tasks = new Future<?>[8];
      for (int t = 0; t < tasks.length; t++) {
        tasks[t] =
            pool.submit(
                () -> {
                  for (int i = 0; i < 1000; i++) {
                    if (meter.recordRequestWithin(500)) {
                      admitted.incrementAndGet();
                    }
                  }
                });
      }
      for (Future<?> task : tasks) {
        task.get(10, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(admitted.get()).isEqualTo(500);
    assertThat(meter.requests()).isEqualTo(500);
  }

  @Test
  void recordThrottleWithinCountsUpToTheCapAndRefusesTheWaitThatWouldCrossIt() {
    assertThat(meter.recordThrottleWithin(Duration.ofSeconds(2), Duration.ofSeconds(3))).isTrue();
    assertThat(meter.recordThrottleWithin(Duration.ofSeconds(1), Duration.ofSeconds(3))).isTrue();
    assertThat(meter.recordThrottleWithin(Duration.ofSeconds(1), Duration.ofSeconds(3)))
        .as("refused, neither counted nor added")
        .isFalse();
    assertThat(meter.throttles()).isEqualTo(2);
    assertThat(meter.throttledTime()).isEqualTo(Duration.ofSeconds(3));
  }

  @Test
  void concurrentWaitsNeverOvershootTheCap() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(8);
    AtomicInteger admitted = new AtomicInteger();
    try {
      Future<?>[] tasks = new Future<?>[8];
      for (int t = 0; t < tasks.length; t++) {
        tasks[t] =
            pool.submit(
                () -> {
                  for (int i = 0; i < 1000; i++) {
                    if (meter.recordThrottleWithin(Duration.ofMillis(10), Duration.ofSeconds(5))) {
                      admitted.incrementAndGet();
                    }
                  }
                });
      }
      for (Future<?> task : tasks) {
        task.get(10, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(admitted.get()).isEqualTo(500);
    assertThat(meter.throttles()).isEqualTo(500);
    assertThat(meter.throttledTime()).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void asAListenerItCountsEveryAttemptAndEveryWait() throws Exception {
    RateLimitListener listener = meter;

    listener.sending();
    listener.throttled(429, Duration.ofSeconds(1));
    listener.sending();
    listener.throttled(429, Duration.ofSeconds(3));
    listener.sending();

    assertThat(meter.throttles()).isEqualTo(2);
    assertThat(meter.throttledTime()).isEqualTo(Duration.ofSeconds(4));
    assertThat(meter.requests()).as("the first attempt and both retries").isEqualTo(3);
  }
}
