package io.opaa.indexing.maintenance;

import io.opaa.indexing.chunk.FullTextChunkStore;
import java.util.UUID;

/**
 * The full-text index fill state of one library (docs/features/hybrid-retrieval.md, "Arbeitspaket
 * 2a"). A chunk's {@code chunk_full_text} row is written in the same transaction as its vector row,
 * so the only state that can lag behind is the <em>version</em> of a row: {@code outdatedChunks}
 * counts the rows not at {@link FullTextChunkStore#CURRENT_TSV_VERSION}, which the lexical path
 * still finds (ADR-0028) and which only the pipeline re-index brings up to date.
 *
 * <p>Every count comes from one query, so no concurrent write can interleave between them.
 */
public record FullTextIndexFillState(
    UUID libraryId, long totalChunks, long indexedChunks, long outdatedChunks) {

  /**
   * {@code true} once no row of this library is left below {@link
   * FullTextChunkStore#CURRENT_TSV_VERSION}.
   */
  public boolean isUpToDate() {
    return outdatedChunks == 0;
  }
}
