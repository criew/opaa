package io.opaa.indexing;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Tracks the processed/failed/skipped counters of a single indexing run and reports them through
 * {@link IndexingJobService} - the shared place for that bookkeeping, so every {@link
 * io.opaa.indexing.source.SourceIndexingExecutor} reuses it instead of repeating it independently.
 */
public final class IndexingRunProgress implements AttachmentProgressSink {

  private final IndexingJobService indexingJobService;
  private final UUID jobId;
  private int processed;
  private int failed;
  private int skipped;

  /**
   * The true count of documents indexed so far - distinct from {@code processed}, which counts the
   * run's own items (files, entries, pages): an item's own document and every attachment indexed
   * for it each add to this count, while only the item itself adds to {@code processed}.
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

  public void recordFailed() {
    failed++;
  }

  public void recordSkipped() {
    skipped++;
  }

  /**
   * Maps one item's {@link FileProcessingResult} onto the counters and the protocol: a rejection
   * ({@code QUOTA_EXCEEDED}, {@code NO_EXTRACTABLE_TEXT}) and {@code SKIPPED} count as skipped,
   * {@code FAILED} as failed, {@code PROCESSED} as processed; the protocol entry, if any, is the
   * one {@link FileProcessingOutcomes#record} writes.
   *
   * @return whether the item was processed
   */
  public boolean recordOutcome(
      FileProcessingResult result,
      String reference,
      IndexingEventSink events,
      Supplier<String> quotaMessage) {
    FileProcessingOutcomes.record(
        events, result, reference, quotaMessage, FileProcessingOutcomes.FAILED_MESSAGE);
    switch (result) {
      case PROCESSED -> {
        recordProcessed();
        return true;
      }
      case FAILED -> recordFailed();
      case SKIPPED, QUOTA_EXCEEDED, NO_EXTRACTABLE_TEXT -> recordSkipped();
    }
    return false;
  }

  /** Documents recorded as failed so far. */
  public int failedCount() {
    return failed;
  }

  /** Source items this run processed so far - the executor's "did this run make progress" check. */
  public int processedCount() {
    return processed;
  }

  public int skippedCount() {
    return skipped;
  }

  /**
   * Records an attachment's outcome for the run's metrics. Only {@code PROCESSED} also counts
   * towards {@code documentsIndexedTotal} (the attachment became a document of its own); skipped
   * and failed attachments leave the document counters alone.
   */
  @Override
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

  /** The attachment counters, for the run's {@link IndexingRunCost}. */
  public int attachmentsProcessed() {
    return attachmentsProcessed;
  }

  public int attachmentsSkipped() {
    return attachmentsSkipped;
  }

  public int attachmentsFailed() {
    return attachmentsFailed;
  }

  /** Reports the current counters. Callers decide when a report is due. */
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
