package io.opaa.indexing;

import java.util.UUID;

/**
 * Tracks the processed/failed/skipped counters of a single indexing run and reports them through
 * {@link IndexingJobService} - the shared place for that bookkeeping, so every {@link
 * SourceIndexingExecutor} reuses it instead of repeating it independently.
 */
final class IndexingRunProgress {

  private final IndexingJobService indexingJobService;
  private final UUID jobId;
  private int processed;
  private int failed;
  private int skipped;

  /**
   * The true count of documents indexed so far - distinct from {@code processed}, which on an
   * RSS_FEED run counts feed entries, not documents: an entry's own document and every attachment
   * indexed for it each add to this count, while only the entry itself adds to {@code processed}.
   * {@link #recordProcessed} increments both; {@link #recordDocumentIndexed} increments only this
   * one, for an attachment document that has no processed/skipped/failed outcome of its own.
   */
  private int documentsIndexedTotal;

  IndexingRunProgress(IndexingJobService indexingJobService, UUID jobId) {
    this.indexingJobService = indexingJobService;
    this.jobId = jobId;
  }

  void setTotal(int total) {
    indexingJobService.setTotalDocuments(jobId, total);
  }

  /** Adds an already-known count of skipped documents (e.g. rejected formats) without reporting. */
  void addSkipped(int count) {
    skipped += count;
  }

  void recordProcessed() {
    processed++;
    documentsIndexedTotal++;
  }

  /**
   * Records an additional document indexed beyond the current entry itself - an RSS attachment. A
   * failed attachment must never call this: {@link RssFeedIndexingExecutor#processAttachment} only
   * reaches this method once the attachment download and format checks it guards have all
   * succeeded.
   */
  void recordDocumentIndexed() {
    documentsIndexedTotal++;
  }

  void recordFailed() {
    failed++;
  }

  /**
   * The number of documents recorded as failed so far - exposed for {@link
   * RssFeedIndexingExecutor}, which needs to know whether a run failed anything before deciding
   * whether the feed's conditional-GET state may be persisted.
   */
  int failedCount() {
    return failed;
  }

  void recordSkipped() {
    skipped++;
  }

  /** Reports the current counters. Callers decide when a report is due, exactly as before. */
  void report() {
    indexingJobService.updateProgress(jobId, processed, failed, skipped, documentsIndexedTotal);
  }

  void complete() {
    indexingJobService.completeJob(jobId, processed, failed, skipped, documentsIndexedTotal);
  }

  void fail(String message) {
    indexingJobService.failJob(jobId, message);
  }
}
