package io.opaa.indexing;

import java.util.UUID;

/**
 * Tracks the processed/failed/skipped counters of a single indexing run and reports them through
 * {@link IndexingJobService}. Both {@link AsyncIndexingExecutor} and {@link UrlIndexingExecutor}
 * used to keep these three counters as local variables and call {@code updateProgress} at the same
 * points independently (ADR-0017) - this class is the shared place for that bookkeeping, so a third
 * executor (e.g. the RSS run in a later issue) reuses it instead of repeating it a third time.
 */
final class IndexingRunProgress {

  private final IndexingJobService indexingJobService;
  private final UUID jobId;
  private int processed;
  private int failed;
  private int skipped;

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
  }

  void recordFailed() {
    failed++;
  }

  /**
   * The number of documents recorded as failed so far - exposed for {@link
   * RssFeedIndexingExecutor}, which needs to know whether a run failed anything before deciding
   * whether the feed's conditional-GET state may be persisted (#490 review, finding 3).
   */
  int failedCount() {
    return failed;
  }

  void recordSkipped() {
    skipped++;
  }

  /** Reports the current counters. Callers decide when a report is due, exactly as before. */
  void report() {
    indexingJobService.updateProgress(jobId, processed, failed, skipped);
  }

  void complete() {
    indexingJobService.completeJob(jobId, processed, failed, skipped);
  }

  void fail(String message) {
    indexingJobService.failJob(jobId, message);
  }
}
