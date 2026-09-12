package io.opaa.auth.local;

import java.time.Duration;

/**
 * The time budget of a maintenance step that sends mail: measured in wall-clock time since the step
 * began, because the synchronous SMTP send - not the number of accounts - is what holds the
 * scheduler thread.
 */
final class MaintenanceBudget {

  private MaintenanceBudget() {}

  static boolean exhausted(long startedNanos, Duration budget) {
    return Duration.ofNanos(System.nanoTime() - startedNanos).compareTo(budget) > 0;
  }
}
