package io.opaa.indexing;

import java.util.UUID;

/**
 * The full-text backfill fill state of one library (docs/features/hybrid-retrieval.md,
 * "Arbeitspaket 2a": "Der Füllstand je Bibliothek ist abfragbarer Zustand, kein Logeintrag").
 * {@code totalChunks} counts every chunk of the library currently in {@code vector_store}; {@code
 * indexedChunks} counts how many rows for it exist in {@code chunk_full_text}; {@code
 * missingChunks} is counted directly via an anti-join ({@code NOT EXISTS}), not derived as {@code
 * totalChunks - indexedChunks} - the two counts are disjoint sets that a stale, orphaned {@code
 * chunk_full_text} row (e.g. left behind by a bug, or by a chunk whose {@code vector_store} row was
 * since deleted through a path that skipped {@link VectorChunkStore}) could otherwise make cancel
 * out, masking a chunk that is genuinely un-indexed (#1047 review, finding 2). All three counts are
 * read together, from the same query, by {@link FullTextBackfillProgressService} - never combined
 * from separate reads a concurrent backfill batch could interleave between.
 */
public record FullTextBackfillProgress(
    UUID libraryId, long totalChunks, long indexedChunks, long missingChunks) {

  /**
   * {@code true} once no chunk of this library is missing its {@code chunk_full_text} row - the
   * condition docs/features/hybrid-retrieval.md's "Paket 2 steht vor Paket 3" ordering names as the
   * gate before the lexical search path may include a library: "Aufgenommen in die Fusion wird der
   * Pfad erst, wenn der Backfill einer Bibliothek abgeschlossen ist." Defined on {@link
   * #missingChunks} directly, not on {@link #totalChunks}/{@link #indexedChunks} - see this
   * record's own Javadoc for why that comparison alone is not safe.
   */
  public boolean isComplete() {
    return missingChunks == 0;
  }
}
