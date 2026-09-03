package io.opaa.indexing.source.confluence;

import java.time.Duration;

/** How the access layer waits on a {@code Retry-After}; replaced in tests to avoid real sleeps. */
@FunctionalInterface
public interface Sleeper {

  void sleep(Duration duration) throws InterruptedException;

  static Sleeper threadSleep() {
    return duration -> Thread.sleep(Math.max(0L, duration.toMillis()));
  }
}
