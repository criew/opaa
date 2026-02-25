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
  }

  @Test
  void completeJobSetsStatusAndCounts() {
    UUID jobId = UUID.randomUUID();
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobRepository.findById(jobId)).thenReturn(Optional.of(job));
    when(indexingJobRepository.save(any(IndexingJob.class))).thenReturn(job);

    service.completeJob(jobId, 10, 2);

    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(job.getDocumentsProcessed()).isEqualTo(10);
    assertThat(job.getDocumentsFailed()).isEqualTo(2);
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

    assertThatThrownBy(() -> service.completeJob(jobId, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
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
