package io.opaa.indexing.maintenance;

import io.opaa.indexing.chunk.FullTextChunkStore;
import java.util.UUID;

/**
 * The full-text index fill state of one library (docs/features/hybrid-retrieval.md, "Arbeitspaket
 * 2a"). A chunk's {@code chunk_full_text} row is written in the same transaction as its vector row,
 * so the only state that can lag behind is the <em>version</em> of a row: {@code outdatedChunks}
 * counts the rows that are not at {@link FullTextChunkStore#CURRENT_TSV_VERSION}, which the lexical
 * path still finds (ADR-0028) and which only the pipeline re-index brings up to date.
 *
 * <p>"Not at" rather than "below": the re-index selects by the same equality test, so a row left
 * above the current version by a rollback converges through the same run instead of sitting in a
 * backlog nothing addresses. {@code indexedChunks} and {@code outdatedChunks} therefore partition
 * the library's rows.
 *
 * <p>Every count comes from one query, so no concurrent write can interleave between them, and each
 * counts only rows whose chunk still exists in the vector store.
 */
public record FullTextIndexFillState(
    UUID libraryId, long totalChunks, long indexedChunks, long outdatedChunks) {

  /**
   * {@code true} once no row of this library is left off {@link
   * FullTextChunkStore#CURRENT_TSV_VERSION} <em>and</em> every chunk of it carries a row at all.
   * The second half is a smoke detector, not a modeled state: no write path can produce a chunk
   * without its full-text row, so a shortfall against {@link #totalChunks} is a finding rather than
   * a backlog. The administration display is driven by {@link #outdatedChunks} alone - that is the
   * state a re-index can act on.
   */
  public boolean isUpToDate() {
    return outdatedChunks == 0 && indexedChunks == totalChunks;
  }
}
