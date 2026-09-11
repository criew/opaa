package io.opaa.library;

import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Strictly monotonic source of the interval boundaries {@link PermissionHistoryService} records:
 * every call returns an instant strictly greater than the one returned before, at the microsecond
 * resolution {@code timestamptz} stores - so two boundaries that differ here still differ once read
 * back from the database. A wall-clock reading that is not greater than the last boundary is
 * replaced by that boundary plus one microsecond.
 *
 * <p>Monotonicity is unconditional, absolute accuracy is not. Within one coarse clock tick the
 * result runs one microsecond per call ahead of the wall clock and is caught up by the next tick.
 * After the wall clock jumps <i>backwards</i> by some delta (NTP step, VM snapshot restore), the
 * lead is that whole delta from the very next call on, and stays until the wall clock has passed
 * the last boundary again - for that span the recorded instants lie in the future and cluster
 * around the pre-jump value. ADR-0032 carries this as a known consequence.
 *
 * <p>Monotonicity holds per process, which is what ADR-0021 (single instance) allows; ADR-0032
 * records the decision and what a multi-instance setup would need instead.
 */
@Component
class PermissionHistoryClock {

  private final InstantSource wallClock;
  private final AtomicReference<Instant> lastBoundary = new AtomicReference<>(Instant.EPOCH);

  PermissionHistoryClock() {
    this(InstantSource.system());
  }

  /**
   * Spring instantiates the no-arg constructor; this one takes a standing or stepping wall clock,
   * the only way to exercise "two changes within one clock tick" without a wait or a retry loop.
   */
  PermissionHistoryClock(InstantSource wallClock) {
    this.wallClock = wallClock;
  }

  Instant nextBoundary() {
    return lastBoundary.updateAndGet(
        previous -> {
          Instant reading = wallClock.instant().truncatedTo(ChronoUnit.MICROS);
          return reading.isAfter(previous) ? reading : previous.plus(1, ChronoUnit.MICROS);
        });
  }
}
