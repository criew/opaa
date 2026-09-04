package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.indexing.FullTextIndexFillState;
import io.opaa.indexing.FullTextIndexFillStateService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The reporting counterpart of the removed completion gate (#1270): it never narrows the search
 * scope, and it does not pay for its answer on every query.
 */
class FullTextIndexCompletenessTest {

  private static final UUID COMPLETE_LIBRARY = UUID.randomUUID();
  private static final UUID INCOMPLETE_LIBRARY = UUID.randomUUID();
  private static final Instant START = Instant.parse("2026-09-04T10:00:00Z");

  private final FullTextIndexFillStateService fillStateService =
      mock(FullTextIndexFillStateService.class);

  private static final class MovableClock extends Clock {
    private Instant now = START;

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }

    void advance(Duration amount) {
      now = now.plus(amount);
    }
  }

  private void stub(UUID libraryId, long missingChunks) {
    when(fillStateService.fillStateForLibrary(libraryId))
        .thenReturn(new FullTextIndexFillState(libraryId, 10, 10 - missingChunks, missingChunks));
  }

  @Test
  void countsOnlyTheLibrariesMissingChunksFromTheFullTextIndex() {
    stub(COMPLETE_LIBRARY, 0);
    stub(INCOMPLETE_LIBRARY, 3);
    FullTextIndexCompleteness completeness =
        new FullTextIndexCompleteness(fillStateService, new MovableClock());

    assertThat(completeness.incompleteLibraryCount(Set.of(COMPLETE_LIBRARY, INCOMPLETE_LIBRARY)))
        .isEqualTo(1);
  }

  /** A completed library is never counted again: completion cannot be lost while a process runs. */
  @Test
  void readsACompletedLibrarysCountOnlyOnce() {
    stub(COMPLETE_LIBRARY, 0);
    MovableClock clock = new MovableClock();
    FullTextIndexCompleteness completeness = new FullTextIndexCompleteness(fillStateService, clock);

    completeness.incompleteLibraryCount(Set.of(COMPLETE_LIBRARY));
    clock.advance(FullTextIndexCompleteness.RECHECK_INTERVAL.multipliedBy(10));
    completeness.incompleteLibraryCount(Set.of(COMPLETE_LIBRARY));

    verify(fillStateService, times(1)).fillStateForLibrary(COMPLETE_LIBRARY);
  }

  /**
   * An incomplete library keeps its answer for the recheck interval and is re-read afterwards - a
   * finished re-index becomes visible without a restart, but not at the price of one count per
   * query.
   */
  @Test
  void reReadsAnIncompleteLibraryOnlyAfterTheRecheckInterval() {
    stub(INCOMPLETE_LIBRARY, 3);
    MovableClock clock = new MovableClock();
    FullTextIndexCompleteness completeness = new FullTextIndexCompleteness(fillStateService, clock);

    assertThat(completeness.incompleteLibraryCount(Set.of(INCOMPLETE_LIBRARY))).isEqualTo(1);
    clock.advance(FullTextIndexCompleteness.RECHECK_INTERVAL.minusSeconds(1));
    assertThat(completeness.incompleteLibraryCount(Set.of(INCOMPLETE_LIBRARY))).isEqualTo(1);
    verify(fillStateService, times(1)).fillStateForLibrary(INCOMPLETE_LIBRARY);

    clock.advance(Duration.ofSeconds(2));
    stub(INCOMPLETE_LIBRARY, 0);
    assertThat(completeness.incompleteLibraryCount(Set.of(INCOMPLETE_LIBRARY))).isZero();
    verify(fillStateService, times(2)).fillStateForLibrary(INCOMPLETE_LIBRARY);
  }
}
