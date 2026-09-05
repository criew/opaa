package io.opaa.searchadmin;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.ContextPrefixRerunProgress;
import io.opaa.indexing.metadata.MetadataBackfillProgress;
import io.opaa.searchadmin.LibrarySearchStatus.IndexCondition;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link LibrarySearchStatus#fullTextIndexCondition()}: a library holding chunks the full-text
 * index does not carry must never look flawlessly READY - it is quietly missing content the lexical
 * search cannot find.
 */
class LibrarySearchStatusTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();

  private LibrarySearchStatus status(long vectorChunkCount, long missing) {
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
        vectorChunkCount - missing,
        missing,
        MetadataBackfillProgress.empty(LIBRARY_ID),
        ContextPrefixRerunProgress.empty(LIBRARY_ID));
  }

  @Test
  void readyWhenNothingIsMissing() {
    assertThat(status(10, 0).fullTextIndexCondition()).isEqualTo(IndexCondition.READY);
  }

  @Test
  void incompleteWhileAChunkIsMissing() {
    assertThat(status(10, 1).fullTextIndexCondition()).isEqualTo(IndexCondition.INCOMPLETE);
  }

  @Test
  void emptyWithoutAnyVectorChunkRegardlessOfTheMissingCount() {
    assertThat(status(0, 0).fullTextIndexCondition()).isEqualTo(IndexCondition.EMPTY);
  }
}
