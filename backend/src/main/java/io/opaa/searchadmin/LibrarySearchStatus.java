package io.opaa.searchadmin;

import io.opaa.indexing.metadata.MetadataBackfillProgress;
import io.opaa.indexing.metadata.ModelExtractionStats;
import java.time.Instant;
import java.util.UUID;

/**
 * One library's index state, as the administration page shows it.
 *
 * <p>{@code fullTextIndexedChunks}/{@code fullTextMissingChunks} are read from {@code
 * io.opaa.indexing.FullTextIndexFillStateService} and counted nowhere else, so no second count with
 * its own logic can contradict this display.
 *
 * @param chunkCount chunks the {@code documents} rows record as produced.
 * @param vectorChunkCount chunks actually present in the vector store. A gap to {@code chunkCount}
 *     means the two stores disagree, which is itself the finding.
 * @param lowChunkDocumentCount documents geführt as indexed with null or auffällig wenige chunks -
 *     a permanent operational metric, not a one-off cleanup number (#1055).
 * @param metadataBackfill the core-metadata extraction state and Füllgrad per field (#1067), read
 *     from the same selection the backfill itself drains.
 * @param modelExtraction the Zählwerk of the model-backed extraction (#1073) - without it the only
 *     feedback about the cost of that capability is the model provider's invoice.
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
    MetadataBackfillProgress metadataBackfill,
    ModelExtractionStats modelExtraction) {

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
   * Incomplete while a chunk lacks its full-text row at the current tsv version - content the
   * lexical search path cannot find, and which no background job repairs since #1270: the remedy is
   * a reindex. A library must never look flawlessly READY while it is quietly missing such content.
   */
  public IndexCondition fullTextIndexCondition() {
    if (vectorChunkCount == 0) {
      return IndexCondition.EMPTY;
    }
    return fullTextMissingChunks > 0 ? IndexCondition.INCOMPLETE : IndexCondition.READY;
  }
}
