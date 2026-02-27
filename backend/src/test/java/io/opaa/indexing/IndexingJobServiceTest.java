package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IndexingJobServiceTest {

  @Mock private IndexingJobRepository indexingJobRepository;
  private IndexingJobService service;

  @BeforeEach
  void setUp() {
    service = new IndexingJobService(indexingJobRepository);
  }

  @Test
  void startJobCreatesRunningJob() {
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobRepository.save(any(IndexingJob.class))).thenReturn(job);

    IndexingJob result = service.startJob();

    assertThat(result.getStatus()).isEqualTo(JobStatus.RUNNING);
    assertThat(result.getStartedAt()).isNotNull();
  }

  @Test
  void completeJobSetsStatusAndCounts() {
    UUID jobId = UUID.randomUUID();
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobRepository.findById(jobId)).thenReturn(Optional.of(job));
    when(indexingJobRepository.save(any(IndexingJob.class))).thenReturn(job);

    service.completeJob(jobId, 10, 2, 5);

    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(job.getDocumentsProcessed()).isEqualTo(10);
    assertThat(job.getDocumentsFailed()).isEqualTo(2);
    assertThat(job.getDocumentsSkipped()).isEqualTo(5);
    assertThat(job.getCompletedAt()).isNotNull();
  }

  @Test
  void failJobSetsStatusAndMessage() {
    UUID jobId = UUID.randomUUID();
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobRepository.findById(jobId)).thenReturn(Optional.of(job));
    when(indexingJobRepository.save(any(IndexingJob.class))).thenReturn(job);

    service.failJob(jobId, "Something went wrong");

    assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(job.getErrorMessage()).isEqualTo("Something went wrong");
    assertThat(job.getCompletedAt()).isNotNull();
  }

  @Test
  void completeJobThrowsForUnknownJob() {
    UUID jobId = UUID.randomUUID();
    when(indexingJobRepository.findById(jobId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.completeJob(jobId, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void setTotalDocumentsSetsCount() {
    UUID jobId = UUID.randomUUID();
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobRepository.findById(jobId)).thenReturn(Optional.of(job));
    when(indexingJobRepository.save(any(IndexingJob.class))).thenReturn(job);

    service.setTotalDocuments(jobId, 15);

    assertThat(job.getDocumentsTotal()).isEqualTo(15);
  }

  @Test
  void updateProgressSetsCountsWithoutCompletingJob() {
    UUID jobId = UUID.randomUUID();
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobRepository.findById(jobId)).thenReturn(Optional.of(job));
    when(indexingJobRepository.save(any(IndexingJob.class))).thenReturn(job);

    service.updateProgress(jobId, 5, 1, 3);

    assertThat(job.getDocumentsProcessed()).isEqualTo(5);
    assertThat(job.getDocumentsFailed()).isEqualTo(1);
    assertThat(job.getDocumentsSkipped()).isEqualTo(3);
    assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
    assertThat(job.getCompletedAt()).isNull();
  }

  @Test
  void getLatestJobReturnsEmptyWhenNoJobs() {
    when(indexingJobRepository.findTopByOrderByStartedAtDesc()).thenReturn(Optional.empty());

    assertThat(service.getLatestJob()).isEmpty();
  }

  @Test
  void getLatestJobReturnsJob() {
    var job = new IndexingJob(JobStatus.COMPLETED);
    when(indexingJobRepository.findTopByOrderByStartedAtDesc()).thenReturn(Optional.of(job));

    assertThat(service.getLatestJob()).contains(job);
  }
}
