package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.opaa.indexing.FullTextBackfillProgress;
import io.opaa.indexing.FullTextBackfillProgressService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The gate that keeps a library out of the lexical path until its full-text backfill has finished
 * (#1048, docs/features/hybrid-retrieval.md, "Arbeitspaket 2a"). Three properties are asserted
 * here, and each one is load-bearing: the gate never widens what it was given, it does not put a
 * table scan into every query, and a completed library is not re-counted for the rest of the
 * process.
 */
class FullTextBackfillGateTest {

  private static final UUID COMPLETE = UUID.randomUUID();
  private static final UUID BACKFILLING = UUID.randomUUID();

  private final FullTextBackfillProgressService progressService =
      mock(FullTextBackfillProgressService.class);
  private final AdvanceableClock clock =
      new AdvanceableClock(Instant.parse("2026-09-01T10:00:00Z"));
  private final FullTextBackfillGate gate = new FullTextBackfillGate(progressService, clock);

  private void stubProgress(UUID libraryId, long missingChunks) {
    stubProgress(libraryId, missingChunks, 0);
  }

  private void stubProgress(UUID libraryId, long missingChunks, long skippedChunks) {
    when(progressService.progressForLibrary(libraryId))
        .thenReturn(
            new FullTextBackfillProgress(
                libraryId, 10, 10 - missingChunks - skippedChunks, missingChunks, skippedChunks));
  }

  /**
   * A half-filled full-text index returns hits and hides the rest - worse than returning nothing,
   * which is why the incomplete library is dropped rather than searched partially.
   */
  @Test
  void anIncompleteLibraryStaysOutWhileACompleteOneIsSearched() {
    stubProgress(COMPLETE, 0);
    stubProgress(BACKFILLING, 4);

    Set<UUID> searchable = gate.searchableLibraries(Set.of(COMPLETE, BACKFILLING));

    assertThat(searchable).containsExactly(COMPLETE);
  }

  /**
   * The gate narrows the permission scope and can never widen it - otherwise it would be a second,
   * weaker permission decision next to {@link SearchScopeStage} (ADR-0008 §5).
   */
  @Test
  void theResultIsAlwaysASubsetOfTheGivenScope() {
    stubProgress(COMPLETE, 0);
    stubProgress(BACKFILLING, 4);

    assertThat(gate.searchableLibraries(Set.of(COMPLETE))).isSubsetOf(Set.of(COMPLETE));
    assertThat(gate.searchableLibraries(Set.of(BACKFILLING))).isSubsetOf(Set.of(BACKFILLING));
    // An empty scope is answered without asking the database at all - and above all never widened
    // into "every library".
    assertThat(gate.searchableLibraries(Set.of())).isEmpty();
    verify(progressService, times(1)).progressForLibrary(COMPLETE);
    verify(progressService, times(1)).progressForLibrary(BACKFILLING);
    verifyNoMoreInteractions(progressService);
  }

  /**
   * The count behind this decision scans {@code vector_store} without an index on the {@code
   * library_id} metadata key; running it per query would put that scan into the retrieval path. An
   * incomplete library is therefore re-counted at most once per {@link
   * FullTextBackfillGate#RECHECK_INTERVAL} - not before, and reliably after.
   */
  @Test
  void anIncompleteLibraryIsRecountedAtMostOncePerInterval() {
    stubProgress(BACKFILLING, 4);

    gate.searchableLibraries(Set.of(BACKFILLING));
    clock.advanceSeconds(FullTextBackfillGate.RECHECK_INTERVAL.toSeconds() - 1);
    gate.searchableLibraries(Set.of(BACKFILLING));

    verify(progressService, times(1)).progressForLibrary(BACKFILLING);

    clock.advanceSeconds(2);
    assertThat(gate.searchableLibraries(Set.of(BACKFILLING))).isEmpty();

    verify(progressService, times(2)).progressForLibrary(BACKFILLING);
  }

  /**
   * Completion is monotone while the process runs: every chunk written after #1047 gets its {@code
   * chunk_full_text} row in the same transaction as its vector row, so a complete library cannot
   * become incomplete again. It is consequently counted once and never again - the only event that
   * does invalidate it, a raised {@code FullTextChunkStore#CURRENT_TSV_VERSION}, arrives with a new
   * process and therefore an empty cache.
   */
  @Test
  void aCompletedLibraryIsCountedOnceAndThenCachedForGood() {
    stubProgress(COMPLETE, 0);

    assertThat(gate.searchableLibraries(Set.of(COMPLETE))).containsExactly(COMPLETE);
    verify(progressService, times(1)).progressForLibrary(COMPLETE);

    clock.advanceSeconds(FullTextBackfillGate.RECHECK_INTERVAL.toSeconds() * 100);
    assertThat(gate.searchableLibraries(Set.of(COMPLETE))).containsExactly(COMPLETE);
    assertThat(gate.searchableLibraries(Set.of(COMPLETE))).containsExactly(COMPLETE);

    verifyNoMoreInteractions(progressService);
  }

  /**
   * A library that finishes its backfill is picked up on the next re-check, not only on restart.
   */
  @Test
  void aLibraryThatFinishesItsBackfillIsPickedUpOnTheNextRecheck() {
    stubProgress(BACKFILLING, 4);
    assertThat(gate.searchableLibraries(Set.of(BACKFILLING))).isEmpty();

    stubProgress(BACKFILLING, 0);
    clock.advanceSeconds(FullTextBackfillGate.RECHECK_INTERVAL.toSeconds() + 1);

    assertThat(gate.searchableLibraries(Set.of(BACKFILLING))).containsExactly(BACKFILLING);
  }

  /**
   * #1093: a library with a permanently skipped ("poison") chunk must not be excluded from the
   * lexical path forever. {@code missingChunks} is what {@link
   * FullTextBackfillProgress#isComplete()} gates on, not {@code skippedChunks} - a library whose
   * only remaining gap is a chunk the backfill gave up on for good is therefore searchable, exactly
   * like one with none. See {@code FullTextBackfillProgress#isComplete}'s own Javadoc for why the
   * alternative (blocking on a skipped chunk too) would silence every other, healthy chunk in the
   * library forever.
   */
  @Test
  void aLibraryWithOnlyPermanentlySkippedChunksIsStillSearchable() {
    stubProgress(BACKFILLING, 0, 3);

    assertThat(gate.searchableLibraries(Set.of(BACKFILLING))).containsExactly(BACKFILLING);
  }

  /** A {@link Clock} whose instant the test moves by hand instead of waiting a minute. */
  private static final class AdvanceableClock extends Clock {

    private Instant instant;

    private AdvanceableClock(Instant instant) {
      this.instant = instant;
    }

    private void advanceSeconds(long seconds) {
      instant = instant.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
