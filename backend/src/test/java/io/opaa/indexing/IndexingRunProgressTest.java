package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IndexingRunProgressTest {

  @Test
  void attachmentOutcomesCountSeparatelyAndOnlyAProcessedOneBecomesADocument() {
    // the attachment share is its own set of counters; the document counters keep their
    // established meaning (an indexed attachment is a document, a skipped or failed one is not).
    IndexingJobService jobService = mock(IndexingJobService.class);
    UUID jobId = UUID.randomUUID();
    IndexingRunProgress progress = new IndexingRunProgress(jobService, jobId);

    progress.recordProcessed();
    progress.recordAttachment(IndexingRunProgress.AttachmentOutcome.PROCESSED);
    progress.recordAttachment(IndexingRunProgress.AttachmentOutcome.PROCESSED);
    progress.recordAttachment(IndexingRunProgress.AttachmentOutcome.SKIPPED);
    progress.recordAttachment(IndexingRunProgress.AttachmentOutcome.FAILED);
    progress.complete();

    assertThat(progress.attachmentsProcessed()).isEqualTo(2);
    assertThat(progress.attachmentsSkipped()).isEqualTo(1);
    assertThat(progress.attachmentsFailed()).isEqualTo(1);
    verify(jobService).completeJob(jobId, 1, 0, 0, 3);
  }
}
