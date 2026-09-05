package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The shared counters and the shared result mapping every connector run reports through. */
class IndexingRunProgressTest {

  private static final String QUOTA_MESSAGE = "Speicherkontingent der Bibliothek erschöpft";

  private final IndexingJobService jobService = mock(IndexingJobService.class);
  private final IndexingEventSink events = mock(IndexingEventSink.class);
  private final UUID jobId = UUID.randomUUID();
  private final IndexingRunProgress progress = new IndexingRunProgress(jobService, jobId);

  @Test
  void attachmentOutcomesCountSeparatelyAndOnlyAProcessedOneBecomesADocument() {
    // the attachment share is its own set of counters; the document counters keep their
    // established meaning (an indexed attachment is a document, a skipped or failed one is not).
    progress.recordProcessed();
    progress.recordAttachment(AttachmentOutcome.PROCESSED);
    progress.recordAttachment(AttachmentOutcome.PROCESSED);
    progress.recordAttachment(AttachmentOutcome.SKIPPED);
    progress.recordAttachment(AttachmentOutcome.FAILED);
    progress.complete();

    assertThat(progress.attachmentsProcessed()).isEqualTo(2);
    assertThat(progress.attachmentsSkipped()).isEqualTo(1);
    assertThat(progress.attachmentsFailed()).isEqualTo(1);
    verify(jobService).completeJob(jobId, 1, 0, 0, 3);
  }

  @Test
  void aProcessedResultCountsAsProcessedAndRecordsNoEvent() {
    boolean processed =
        progress.recordOutcome(FileProcessingResult.PROCESSED, "datei.txt", events, this::quota);

    assertThat(processed).isTrue();
    assertThat(progress.processedCount()).isEqualTo(1);
    verifyNoInteractions(events);
    progress.complete();
    verify(jobService).completeJob(jobId, 1, 0, 0, 1);
  }

  @Test
  void aSkippedResultCountsAsSkippedAndRecordsNoEvent() {
    boolean processed =
        progress.recordOutcome(FileProcessingResult.SKIPPED, "datei.txt", events, this::quota);

    assertThat(processed).isFalse();
    assertThat(progress.skippedCount()).isEqualTo(1);
    verifyNoInteractions(events);
  }

  @Test
  void anExceededQuotaCountsAsSkippedAndIsRejectedWithTheQuotaMessage() {
    boolean processed =
        progress.recordOutcome(
            FileProcessingResult.QUOTA_EXCEEDED, "datei.txt", events, this::quota);

    assertThat(processed).isFalse();
    assertThat(progress.skippedCount()).isEqualTo(1);
    verify(events).record(IndexingEventCategory.REJECTED, QUOTA_MESSAGE, "datei.txt");
  }

  @Test
  void missingExtractableTextCountsAsSkippedAndIsRejectedWithItsOwnMessage() {
    boolean processed =
        progress.recordOutcome(
            FileProcessingResult.NO_EXTRACTABLE_TEXT, "scan.pdf", events, this::quota);

    assertThat(processed).isFalse();
    assertThat(progress.skippedCount()).isEqualTo(1);
    verify(events)
        .record(
            IndexingEventCategory.REJECTED,
            DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE,
            "scan.pdf");
  }

  @Test
  void aFailedResultCountsAsFailedAndIsRecordedAsAnError() {
    boolean processed =
        progress.recordOutcome(FileProcessingResult.FAILED, "kaputt.pdf", events, this::quota);

    assertThat(processed).isFalse();
    assertThat(progress.failedCount()).isEqualTo(1);
    verify(events)
        .record(IndexingEventCategory.ERROR, FileProcessingOutcomes.FAILED_MESSAGE, "kaputt.pdf");
    progress.complete();
    verify(jobService).completeJob(jobId, 0, 1, 0, 0);
  }

  @Test
  void theQuotaMessageIsOnlyResolvedWhenTheQuotaWasExceeded() {
    progress.recordOutcome(
        FileProcessingResult.PROCESSED,
        "datei.txt",
        events,
        () -> {
          throw new AssertionError("must not be resolved");
        });
    progress.recordOutcome(
        FileProcessingResult.FAILED,
        "datei.txt",
        events,
        () -> {
          throw new AssertionError("must not be resolved");
        });

    verify(events).record(any(), any(), any());
  }

  private String quota() {
    return QUOTA_MESSAGE;
  }
}
