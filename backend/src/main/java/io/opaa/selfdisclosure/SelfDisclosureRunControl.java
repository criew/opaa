package io.opaa.selfdisclosure;

import io.opaa.selfdisclosure.SelfDisclosureAbort.AbortCause;
import java.util.concurrent.ScheduledFuture;

/**
 * The control of one running export: the watchdog of {@link SelfDisclosureRunRegistry} and the
 * shutdown set the abort, the streaming thread raises it at its next record, between categories and
 * before counting the export. An export whose exporter reads long without output is therefore told
 * to end on time, and ends at its next write.
 */
final class SelfDisclosureRunControl {

  private volatile AbortCause abort;
  private volatile String reason;
  private ScheduledFuture<?> watchdog;

  /** Tells the export to end with {@code cause}; the first cause wins. */
  synchronized void abort(AbortCause cause, String why) {
    if (abort == null) {
      abort = cause;
      reason = why;
    }
  }

  /** Throws {@link SelfDisclosureAbort} once the export has been told to end. */
  void check() {
    AbortCause cause = abort;
    if (cause != null) {
      throw new SelfDisclosureAbort(cause, reason);
    }
  }

  synchronized void watchedBy(ScheduledFuture<?> future) {
    this.watchdog = future;
  }

  synchronized void stopWatching() {
    if (watchdog != null) {
      watchdog.cancel(false);
    }
  }
}
