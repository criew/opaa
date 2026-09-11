package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The unit-level half of #1497: every boundary the permission history records must be strictly
 * greater than the one before it, whatever the underlying wall clock does. The integration-level
 * half - that a state which only existed within one such tick stays reconstructable - is {@link
 * PermissionHistoryServiceIntegrationTest}.
 */
class PermissionHistoryClockTest {

  private static final Instant TICK = Instant.parse("2026-03-03T10:15:30.123456Z");

  @Test
  void aStandingWallClockStillYieldsStrictlyIncreasingBoundaries() {
    PermissionHistoryClock clock = new PermissionHistoryClock(InstantSource.fixed(TICK));

    List<Instant> boundaries = IntStream.range(0, 5).mapToObj(i -> clock.nextBoundary()).toList();

    assertThat(boundaries).isSorted().doesNotHaveDuplicates();
    assertThat(boundaries)
        .containsExactly(
            TICK,
            TICK.plus(1, ChronoUnit.MICROS),
            TICK.plus(2, ChronoUnit.MICROS),
            TICK.plus(3, ChronoUnit.MICROS),
            TICK.plus(4, ChronoUnit.MICROS));
  }

  @Test
  void aWallClockStepBackwardsDoesNotProduceABoundaryBeforeTheLastOne() {
    SteerableClock wallClock = new SteerableClock(TICK);
    PermissionHistoryClock clock = new PermissionHistoryClock(wallClock);

    Instant first = clock.nextBoundary();
    wallClock.set(TICK.minusSeconds(30));
    Instant second = clock.nextBoundary();

    assertThat(second).isAfter(first);
  }

  @Test
  void aWallClockThatHasMovedOnIsUsedAgainInsteadOfCountingMicrosecondsForever() {
    SteerableClock wallClock = new SteerableClock(TICK);
    PermissionHistoryClock clock = new PermissionHistoryClock(wallClock);

    clock.nextBoundary();
    clock.nextBoundary();
    wallClock.set(TICK.plusMillis(4));

    assertThat(clock.nextBoundary()).isEqualTo(TICK.plusMillis(4));
  }

  @Test
  void subMicrosecondWallClockPrecisionIsTruncatedAwayRatherThanLostInTheDatabase() {
    // timestamptz keeps microseconds: two boundaries that only differ in nanoseconds would be one
    // and the same value once stored, so the truncation has to happen before the monotonic guard.
    SteerableClock wallClock = new SteerableClock(TICK.plusNanos(700));
    PermissionHistoryClock clock = new PermissionHistoryClock(wallClock);

    Instant first = clock.nextBoundary();
    wallClock.set(TICK.plusNanos(900));
    Instant second = clock.nextBoundary();

    assertThat(first).isEqualTo(TICK);
    assertThat(second).isEqualTo(TICK.plus(1, ChronoUnit.MICROS));
  }

  @Test
  void concurrentCallersNeverShareABoundary() throws Exception {
    PermissionHistoryClock clock = new PermissionHistoryClock(InstantSource.fixed(TICK));
    int callers = 16;
    int perCaller = 250;

    try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
      List<Callable<List<Instant>>> tasks =
          IntStream.range(0, callers)
              .<Callable<List<Instant>>>mapToObj(
                  i ->
                      () ->
                          IntStream.range(0, perCaller)
                              .mapToObj(n -> clock.nextBoundary())
                              .toList())
              .toList();
      Set<Instant> distinct =
          pool.invokeAll(tasks).stream()
              .map(PermissionHistoryClockTest::join)
              .flatMap(List::stream)
              .collect(Collectors.toSet());

      assertThat(distinct).hasSize(callers * perCaller);
    }
  }

  private static List<Instant> join(Future<List<Instant>> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** A wall clock whose reading only changes when the test says so. */
  private static final class SteerableClock implements InstantSource {

    private volatile Instant reading;

    private SteerableClock(Instant reading) {
      this.reading = reading;
    }

    private void set(Instant next) {
      this.reading = next;
    }

    @Override
    public Instant instant() {
      return reading;
    }
  }
}
