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
 * out, masking a chunk that is genuinely un-indexed. All counts are read together, from the same
 * query, by {@link FullTextBackfillProgressService} - never combined from separate reads a
 * concurrent backfill batch could interleave between.
 *
 * <p>{@code skippedChunks} (#1093) counts chunks {@code io.opaa.indexing.FullTextBackfillService}
 * permanently gave up on ("poison chunks" - see that class's own Javadoc) rather than left pending;
 * it is disjoint from {@code missingChunks}, which counts only chunks the backfill has not yet
 * given a final answer for. A skipped chunk is therefore visible here, not silently absorbed into
 * either {@code indexedChunks} (it never indexed) or {@code missingChunks} (it is not pending
 * retry) - see this record's own {@link #isComplete()} Javadoc for why it must not count toward
 * {@code missingChunks}.
 */
public record FullTextBackfillProgress(
    UUID libraryId, long totalChunks, long indexedChunks, long missingChunks, long skippedChunks) {

  /**
   * {@code true} once no chunk of this library is still pending a full-text backfill attempt - the
   * condition docs/features/hybrid-retrieval.md's "Paket 2 steht vor Paket 3" ordering names as the
   * gate before the lexical search path may include a library: "Aufgenommen in die Fusion wird der
   * Pfad erst, wenn der Backfill einer Bibliothek abgeschlossen ist." Defined on {@link
   * #missingChunks} directly, not on {@link #totalChunks}/{@link #indexedChunks} - see this
   * record's own Javadoc for why that comparison alone is not safe.
   *
   * <p><b>Deliberately not gated on {@link #skippedChunks} being zero (#1093).</b> A permanently
   * skipped chunk will never resolve on its own - counting it toward incompleteness would make a
   * library with even one poison chunk look "incomplete" forever, and {@code
   * io.opaa.query.FullTextBackfillGate} caches exactly that answer to keep the lexical path from
   * ever searching the library - worse than the half-filled index the gate exists to prevent in the
   * first place, since it would silence every other, healthy chunk in that library too. A skipped
   * chunk remains visible via {@link #skippedChunks} instead: an operator can see and act on it
   * without the gate being held hostage by it.
   */
  public boolean isComplete() {
    return missingChunks == 0;
  }
}
