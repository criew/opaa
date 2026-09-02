package io.opaa.searchadmin;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.searchadmin.LibrarySearchStatus.IndexCondition;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link LibrarySearchStatus#fullTextIndexCondition()}'s two ways to be incomplete (#1093 review):
 * a chunk still pending its first backfill attempt, and one the backfill permanently gave up on.
 * Both must keep the library from looking flawlessly READY - only the first also keeps {@code
 * io.opaa.query.FullTextBackfillGate} closed (see {@link LibrarySearchStatus}'s own Javadoc for why
 * the two are deliberately not the same condition there).
 */
class LibrarySearchStatusTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();

  private LibrarySearchStatus status(long vectorChunkCount, long missing, long skipped) {
    return new LibrarySearchStatus(
        LIBRARY_ID,
        "Satzungen",
        1,
        1,
        0,
        0,
        0,
        vectorChunkCount,
        vectorChunkCount,
        Instant.EPOCH,
        vectorChunkCount - missing - skipped,
        missing,
        skipped);
  }

  @Test
  void readyWhenNothingIsMissingOrSkipped() {
    assertThat(status(10, 0, 0).fullTextIndexCondition()).isEqualTo(IndexCondition.READY);
  }

  @Test
  void incompleteWhileAChunkIsStillPending() {
    assertThat(status(10, 1, 0).fullTextIndexCondition()).isEqualTo(IndexCondition.INCOMPLETE);
  }

  /**
   * The condition Blocker 2 of the #1093 review added: a library with only permanently skipped
   * chunks (nothing pending) must not read as READY - it is quietly missing content the lexical
   * search can never find, even though {@link io.opaa.query.FullTextBackfillGate} still searches
   * it.
   */
  @Test
  void incompleteWhenAChunkIsPermanentlySkippedEvenWithNothingPending() {
    assertThat(status(10, 0, 1).fullTextIndexCondition()).isEqualTo(IndexCondition.INCOMPLETE);
  }

  @Test
  void emptyWithoutAnyVectorChunkRegardlessOfMissingOrSkippedCounts() {
    assertThat(status(0, 0, 0).fullTextIndexCondition()).isEqualTo(IndexCondition.EMPTY);
  }
}
