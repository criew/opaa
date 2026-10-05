package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.format.DocumentService;
import io.opaa.indexing.attachment.AttachmentOutcome;
import io.opaa.indexing.document.DocumentIngestOutcomes;
import io.opaa.indexing.document.DocumentIngestOutcomes.QuotaMessages;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingEventSink;
import io.opaa.indexing.job.IndexingJobService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The shared counters and the shared result mapping every connector run reports through. */
class IndexingRunProgressTest {

  private static final String QUOTA_MESSAGE = "Speicherkontingent der Bibliothek erschöpft";
  private static final String PERSONAL_QUOTA_MESSAGE =
      "Speicherkontingent Ihrer privaten Bibliotheken erschöpft";
  private static final QuotaMessages QUOTA =
      new QuotaMessages(() -> QUOTA_MESSAGE, () -> PERSONAL_QUOTA_MESSAGE);

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
        progress.recordOutcome(DocumentIngestResult.PROCESSED, "datei.txt", events, QUOTA);

    assertThat(processed).isTrue();
    assertThat(progress.processedCount()).isEqualTo(1);
    verifyNoInteractions(events);
    progress.complete();
    verify(jobService).completeJob(jobId, 1, 0, 0, 1);
  }

  @Test
  void aSkippedResultCountsAsSkippedAndRecordsNoEvent() {
    boolean processed =
        progress.recordOutcome(DocumentIngestResult.SKIPPED, "datei.txt", events, QUOTA);

    assertThat(processed).isFalse();
    assertThat(progress.skippedCount()).isEqualTo(1);
    verifyNoInteractions(events);
  }

  @Test
  void anExceededQuotaCountsAsSkippedAndIsRejectedWithTheQuotaMessage() {
    boolean processed =
        progress.recordOutcome(DocumentIngestResult.QUOTA_EXCEEDED, "datei.txt", events, QUOTA);

    assertThat(processed).isFalse();
    assertThat(progress.skippedCount()).isEqualTo(1);
    verify(events).record(IndexingEventCategory.REJECTED, QUOTA_MESSAGE, "datei.txt");
  }

  /** Like the library's quota, plus the mark the frame completes the run under. */
  @Test
  void anExceededPersonalQuotaCountsAsSkippedAndIsRejectedWithTheOwnersMessage() {
    assertThat(progress.personalQuotaReached()).isFalse();

    boolean processed =
        progress.recordOutcome(
            DocumentIngestResult.PERSONAL_QUOTA_EXCEEDED, "datei.txt", events, QUOTA);

    assertThat(processed).isFalse();
    assertThat(progress.skippedCount()).isEqualTo(1);
    assertThat(progress.personalQuotaReached()).isTrue();
    verify(events).record(IndexingEventCategory.REJECTED, PERSONAL_QUOTA_MESSAGE, "datei.txt");
  }

  @Test
  void anAttachmentAtThePersonalQuotaMarksTheRunToo() {
    progress.recordPersonalQuotaReached();

    assertThat(progress.personalQuotaReached()).isTrue();
  }

  @Test
  void missingExtractableTextCountsAsSkippedAndIsRejectedWithItsOwnMessage() {
    boolean processed =
        progress.recordOutcome(DocumentIngestResult.NO_EXTRACTABLE_TEXT, "scan.pdf", events, QUOTA);

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
        progress.recordOutcome(DocumentIngestResult.FAILED, "kaputt.pdf", events, QUOTA);

    assertThat(processed).isFalse();
    assertThat(progress.failedCount()).isEqualTo(1);
    verify(events)
        .record(IndexingEventCategory.ERROR, DocumentIngestOutcomes.FAILED_MESSAGE, "kaputt.pdf");
    progress.complete();
    verify(jobService).completeJob(jobId, 0, 1, 0, 0);
  }

  @Test
  void theQuotaMessageIsOnlyResolvedWhenTheQuotaWasExceeded() {
    QuotaMessages unresolvable = new QuotaMessages(this::unresolvable, this::unresolvable);
    progress.recordOutcome(DocumentIngestResult.PROCESSED, "datei.txt", events, unresolvable);
    progress.recordOutcome(DocumentIngestResult.FAILED, "datei.txt", events, unresolvable);

    verify(events).record(any(), any(), any());
  }

  private String unresolvable() {
    throw new AssertionError("must not be resolved");
  }

  @Test
  void aCompletedRunThatIndexedADocumentRecordsAContentChange() {
    when(jobService.completeJob(jobId, 1, 0, 0, 1)).thenReturn(true);
    progress.recordProcessed();

    progress.complete();

    verify(jobService).recordContentChange(jobId);
  }

  @Test
  void aRunWithoutAnIndexedDocumentOrAlreadyRecoveredRecordsNone() {
    when(jobService.completeJob(any(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(true);
    progress.recordSkipped();
    progress.complete();

    IndexingRunProgress recovered = new IndexingRunProgress(jobService, jobId);
    when(jobService.completeJob(jobId, 1, 0, 0, 1)).thenReturn(false);
    recovered.recordProcessed();
    recovered.complete();

    verify(jobService, never()).recordContentChange(any());
  }
}
