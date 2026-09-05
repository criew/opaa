package io.opaa.indexing;

import java.util.UUID;

/**
 * The full-text index fill state of one library (docs/features/hybrid-retrieval.md, "Arbeitspaket
 * 2a": "Der Füllstand je Bibliothek ist abfragbarer Zustand, kein Logeintrag"). {@code totalChunks}
 * counts every chunk of the library currently in {@code vector_store}; {@code indexedChunks} counts
 * how many rows for it exist in {@code chunk_full_text}; {@code missingChunks} is counted directly
 * via an anti-join ({@code NOT EXISTS}), not derived as {@code totalChunks - indexedChunks} - the
 * two counts are disjoint sets that a stale, orphaned {@code chunk_full_text} row (e.g. left behind
 * by a bug, or by a chunk whose {@code vector_store} row was since deleted through a path that
 * skipped {@link VectorChunkStore}) could otherwise make cancel out, masking a chunk that is
 * genuinely un-indexed. All counts are read together, from the same query, by {@link
 * FullTextIndexFillStateService} - never combined from separate reads a concurrent write could
 * interleave between.
 *
 * <p>Nothing fills a gap after the fact: the full-text row is written in the same transaction as
 * the vector row (see {@link VectorChunkStore#addChunks}), so a nonzero {@code missingChunks} is an
 * operational finding - visible on the administration page, resolved by a reindex, never by a
 * background job.
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
