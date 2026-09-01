package io.opaa.searchadmin;

import java.time.Instant;
import java.util.UUID;

/**
 * One library's index state, as the administration page shows it.
 *
 * <p>{@code fullTextMissingChunks} is read from the same query the full-text completion gate reads
 * (see {@code io.opaa.indexing.FullTextBackfillProgressService}) - it is not counted a second time
 * here, so the display and the gate cannot drift apart.
 *
 * @param chunkCount chunks the {@code documents} rows record as produced.
 * @param vectorChunkCount chunks actually present in the vector store. A gap to {@code chunkCount}
 *     means the two stores disagree, which is itself the finding.
 * @param lowChunkDocumentCount documents geführt as indexed with null or auffällig wenige chunks -
 *     a permanent operational metric, not a one-off cleanup number (#1055).
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
    long fullTextMissingChunks) {

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
   * Incomplete for as long as one chunk lacks its full-text row - the exact condition that keeps
   * the lexical path from searching this library at all.
   */
  public IndexCondition fullTextIndexCondition() {
    if (vectorChunkCount == 0) {
      return IndexCondition.EMPTY;
    }
    return fullTextMissingChunks > 0 ? IndexCondition.INCOMPLETE : IndexCondition.READY;
  }
}
