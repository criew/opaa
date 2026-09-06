package io.opaa.sourceaccess;

import java.time.Duration;

/** How a wait on a {@code Retry-After} is performed; replaced in tests to avoid real sleeps. */
@FunctionalInterface
public interface Sleeper {

  void sleep(Duration duration) throws InterruptedException;

  static Sleeper threadSleep() {
    return duration -> Thread.sleep(Math.max(0L, duration.toMillis()));
  }
}
