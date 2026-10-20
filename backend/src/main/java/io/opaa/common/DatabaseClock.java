package io.opaa.common;

import java.time.Instant;

/**
 * The clock every instance of an installation shares: the clock of the database (ADR-0053,
 * Entscheidung 9). A point in time that another instance reads and compares - a heartbeat, a due
 * time, the order of the audit log - comes from here, or straight from {@code clock_timestamp()}
 * inside the statement that writes or compares it. A point in time only this process compares comes
 * from the {@link java.time.Clock} bean of {@link ClockConfiguration}.
 *
 * <p>Each call costs a round trip, and its reading has the microsecond resolution of {@code
 * timestamptz}. It is not monotonic: the database clock may step back like any wall clock.
 */
@FunctionalInterface
public interface DatabaseClock {

  /** The current time of the database, {@code clock_timestamp()}, not the transaction start. */
  Instant now();
}
