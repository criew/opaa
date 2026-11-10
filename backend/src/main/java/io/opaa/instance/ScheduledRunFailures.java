package io.opaa.instance;

/**
 * Lets a {@code @Scheduled} run that catches and logs its own failure, to keep going or to keep a
 * message out of the log, still count as failed in {@code opaa.scheduled.failures} (ADR-0071): it
 * calls {@link #report()} where it catches, and {@link ScheduledJobMetrics} reads the mark when the
 * run ends. The mark lives on the run's thread; work handed to another thread does not reach it.
 */
public final class ScheduledRunFailures {

  private static final ThreadLocal<Boolean> FAILED = new ThreadLocal<>();

  private ScheduledRunFailures() {}

  /** Marks the run on this thread as failed; outside a scheduled run nothing reads the mark. */
  public static void report() {
    FAILED.set(Boolean.TRUE);
  }

  /** Whether the run on this thread reported a failure since the last {@link #clear()}. */
  static boolean reported() {
    return FAILED.get() != null;
  }

  /** Forgets the mark of this thread. */
  static void clear() {
    FAILED.remove();
  }
}
