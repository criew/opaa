package io.opaa.indexing;

import java.util.UUID;

/**
 * Tracks the processed/failed/skipped counters of a single indexing run and reports them through
 * {@link IndexingJobService} - the shared place for that bookkeeping, so every {@link
 * SourceIndexingExecutor} reuses it instead of repeating it independently.
 */
public final class IndexingRunProgress implements AttachmentProgressSink {

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

  /** The attachment share of the counters above - see {@link #recordAttachment}. */
  private int attachmentsProcessed;

  private int attachmentsSkipped;
  private int attachmentsFailed;

  public IndexingRunProgress(IndexingJobService indexingJobService, UUID jobId) {
    this.indexingJobService = indexingJobService;
    this.jobId = jobId;
  }

  public void setTotal(int total) {
    indexingJobService.setTotalDocuments(jobId, total);
  }

  /** Adds an already-known count of skipped documents (e.g. rejected formats) without reporting. */
  public void addSkipped(int count) {
    skipped += count;
  }

  public void recordProcessed() {
    processed++;
    documentsIndexedTotal++;
  }

  /**
   * Records an additional document indexed beyond the current entry itself - an RSS attachment. A
   * failed attachment must never call this: {@code AttachmentIndexer#indexOne} only reaches this
   * method once the attachment download and format checks it guards have all succeeded.
   */
  @Override
  public void recordDocumentIndexed() {
    documentsIndexedTotal++;
  }

  public void recordFailed() {
    failed++;
  }

  /**
   * The number of documents recorded as failed so far - exposed for {@link
   * RssFeedIndexingExecutor}, which needs to know whether a run failed anything before deciding
   * whether the feed's conditional-GET state may be persisted.
   */
  public int failedCount() {
    return failed;
  }

  /** Source items this run processed so far - the executor's "did this run make progress" check. */
  public int processedCount() {
    return processed;
  }

  public void recordSkipped() {
    skipped++;
  }

  /** The outcome of one attachment. */
  public enum AttachmentOutcome {
    PROCESSED,
    SKIPPED,
    FAILED
  }

  /**
   * Records an attachment's outcome for the run's metrics. Only {@code PROCESSED} also counts
   * towards {@code documentsIndexedTotal} (the attachment became a document of its own); skipped
   * and failed attachments leave the document counters alone, as before.
   */
  public void recordAttachment(AttachmentOutcome outcome) {
    switch (outcome) {
      case PROCESSED -> {
        attachmentsProcessed++;
        documentsIndexedTotal++;
      }
      case SKIPPED -> attachmentsSkipped++;
      case FAILED -> attachmentsFailed++;
    }
  }

  /** The attachment counters, for the executor's {@link IndexingRunCost}. */
  public int attachmentsProcessed() {
    return attachmentsProcessed;
  }

  public int attachmentsSkipped() {
    return attachmentsSkipped;
  }

  public int attachmentsFailed() {
    return attachmentsFailed;
  }

  /** Reports the current counters. Callers decide when a report is due, exactly as before. */
  public void report() {
    indexingJobService.updateProgress(jobId, processed, failed, skipped, documentsIndexedTotal);
  }

  public void complete() {
    indexingJobService.completeJob(jobId, processed, failed, skipped, documentsIndexedTotal);
  }

  public void fail(String message) {
    indexingJobService.failJob(jobId, message);
  }
}
