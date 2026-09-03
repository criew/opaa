package io.opaa.searchadmin;

import java.time.Instant;
import java.util.UUID;

/**
 * One library's index state, as the administration page shows it.
 *
 * <p>{@code fullTextMissingChunks}/{@code fullTextSkippedChunks} are read from the same query the
 * full-text completion gate reads (see {@code io.opaa.indexing.FullTextBackfillProgressService}) -
 * neither is counted a second time here, so the display and the gate cannot drift apart on the
 * numbers themselves.
 *
 * <p><b>The two can still disagree on the resulting decision:</b> {@code
 * io.opaa.query.FullTextBackfillGate} caches "complete" for a library's remaining process lifetime,
 * so a library that gains never-backfilled chunks afterwards shows {@link
 * IndexCondition#INCOMPLETE} here while the gate keeps searching it regardless - deliberate, not a
 * bug to fix here.
 *
 * @param chunkCount chunks the {@code documents} rows record as produced.
 * @param vectorChunkCount chunks actually present in the vector store. A gap to {@code chunkCount}
 *     means the two stores disagree, which is itself the finding.
 * @param lowChunkDocumentCount documents geführt as indexed with null or auffällig wenige chunks -
 *     a permanent operational metric, not a one-off cleanup number (#1055).
 * @param fullTextSkippedChunks chunks the full-text backfill permanently gave up indexing after
 *     repeated failures ("poison chunks", #1093) - see {@link #fullTextIndexCondition()} for why
 *     this, unlike {@code fullTextMissingChunks}, does not by itself keep the search gate closed,
 *     but still must not go unseen on this page.
 */
public record LibrarySearchStatus(
    UUID libraryId,
    String libraryName,
    long documentCount,
    long indexedDocumentCount,
    long pendingDocumentCount,
    long failedDocumentCount,
    long lowChunkDocumentCount,
    long chunkCount,
    long vectorChunkCount,
    Instant lastIndexedAt,
    long fullTextIndexedChunks,
    long fullTextMissingChunks,
    long fullTextSkippedChunks) {

  /** Whether an index holds what it is supposed to hold. */
  public enum IndexCondition {
    EMPTY,
    READY,
    INCOMPLETE
  }

  /** Empty without chunks, incomplete while documents are still waiting, otherwise ready. */
  public IndexCondition vectorIndexCondition() {
    if (vectorChunkCount == 0) {
      return IndexCondition.EMPTY;
    }
    return pendingDocumentCount > 0 ? IndexCondition.INCOMPLETE : IndexCondition.READY;
  }

  /**
   * Incomplete while a chunk still lacks its full-text row, or once one has been permanently given
   * up on. The two are not the same condition - {@code io.opaa.query.FullTextBackfillGate} only
   * closes on the former, since a chunk it will never resolve on its own must not silence every
   * other, healthy chunk of the library - but this display deliberately does not distinguish them:
   * a library must never look flawlessly READY while it is quietly missing content the lexical
   * search can never find. {@link #fullTextSkippedChunks} stays separately visible for an operator
   * to tell the two apart.
   */
  public IndexCondition fullTextIndexCondition() {
    if (vectorChunkCount == 0) {
      return IndexCondition.EMPTY;
    }
    return fullTextMissingChunks > 0 || fullTextSkippedChunks > 0
        ? IndexCondition.INCOMPLETE
        : IndexCondition.READY;
  }
}
