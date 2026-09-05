package io.opaa.indexing;

import java.util.UUID;

/**
 * The full-text index fill state of one library (docs/features/hybrid-retrieval.md, "Arbeitspaket
 * 2a"). {@code missingChunks} is counted directly via an anti-join, never derived as {@code
 * totalChunks - indexedChunks}: a stale, orphaned {@code chunk_full_text} row could otherwise make
 * the two cancel out and mask a genuinely un-indexed chunk. All counts come from one query, so no
 * concurrent write can interleave between them.
 *
 * <p>Nothing fills a gap after the fact - the full-text row is written in the same transaction as
 * the vector row - so a nonzero {@code missingChunks} is an operational finding, resolved by a
 * reindex.
 */
public record FullTextIndexFillState(
    UUID libraryId, long totalChunks, long indexedChunks, long missingChunks) {

  /**
   * {@code true} once every chunk of this library carries a {@code chunk_full_text} row at the
   * current {@link FullTextChunkStore#CURRENT_TSV_VERSION}. Defined on {@link #missingChunks}
   * directly, not on {@link #totalChunks}/{@link #indexedChunks} - see this record's own Javadoc
   * for why that comparison alone is not safe.
   */
  public boolean isComplete() {
    return missingChunks == 0;
  }
}
