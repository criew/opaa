package io.opaa.searchadmin;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.maintenance.ContextPrefixRerunProgress;
import io.opaa.indexing.metadata.MetadataBackfillProgress;
import io.opaa.indexing.metadata.ModelExtractionStats;
import io.opaa.searchadmin.LibrarySearchStatus.IndexCondition;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link LibrarySearchStatus#fullTextIndexCondition()}: a library whose full-text rows still sit
 * below the current tsv version must never look flawlessly READY - its re-index is outstanding.
 */
class LibrarySearchStatusTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();

  private LibrarySearchStatus status(long vectorChunkCount, long outdated) {
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
        vectorChunkCount - outdated,
        outdated,
        MetadataBackfillProgress.empty(LIBRARY_ID),
        ModelExtractionStats.empty(LIBRARY_ID),
        ContextPrefixRerunProgress.empty(LIBRARY_ID));
  }

  @Test
  void readyWithoutAnyBacklog() {
    assertThat(status(10, 0).fullTextIndexCondition()).isEqualTo(IndexCondition.READY);
  }

  @Test
  void outdatedWhileARowSitsBelowTheCurrentVersion() {
    assertThat(status(10, 1).fullTextIndexCondition()).isEqualTo(IndexCondition.OUTDATED);
  }

  @Test
  void emptyWithoutAnyVectorChunkRegardlessOfTheBacklog() {
    assertThat(status(0, 0).fullTextIndexCondition()).isEqualTo(IndexCondition.EMPTY);
  }
}
