package io.opaa.indexing;

import java.util.UUID;

/**
 * The full-text backfill fill state of one library (docs/features/hybrid-retrieval.md,
 * "Arbeitspaket 2a": "Der Füllstand je Bibliothek ist abfragbarer Zustand, kein Logeintrag").
 * {@code totalChunks} counts every chunk of the library currently in {@code vector_store}; {@code
 * indexedChunks} counts how many of those already carry a {@code chunk_full_text} row. Both counts
 * are read together, from the same query, by {@link FullTextBackfillProgressService} - never
 * combined from two separate reads a concurrent backfill batch could interleave between.
 */
public record FullTextBackfillProgress(UUID libraryId, long totalChunks, long indexedChunks) {

  /**
   * {@code true} once every chunk currently in {@code vector_store} for this library also has a
   * {@code chunk_full_text} row - the condition docs/features/hybrid-retrieval.md's "Paket 2 steht
   * vor Paket 3" ordering names as the gate before the lexical search path may include a library:
   * "Aufgenommen in die Fusion wird der Pfad erst, wenn der Backfill einer Bibliothek abgeschlossen
   * ist."
   */
  public boolean isComplete() {
    return indexedChunks >= totalChunks;
  }
}
