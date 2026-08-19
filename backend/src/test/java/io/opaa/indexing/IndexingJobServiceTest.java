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

    IndexingJob result = service.startJob(UUID.randomUUID());

    assertThat(result.getStatus()).isEqualTo(JobStatus.RUNNING);
    assertThat(result.getStartedAt()).isNotNull();
  }

  @Test
  void startJobRecordsTheTargetLibrary() {
    // #419 acceptance criteria: the indexing job records its target library.
    UUID libraryId = UUID.randomUUID();
    when(indexingJobRepository.save(any(IndexingJob.class))).thenAnswer(inv -> inv.getArgument(0));

    IndexingJob result = service.startJob(libraryId);

    assertThat(result.getLibraryId()).isEqualTo(libraryId);
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
  void getLatestJobReturnsEmptyWhenTheLibraryNeverRan() {
    UUID libraryId = UUID.randomUUID();
    when(indexingJobRepository.findTopByLibraryIdOrderByStartedAtDesc(libraryId))
        .thenReturn(Optional.empty());

    assertThat(service.getLatestJob(libraryId)).isEmpty();
  }

  @Test
  void getLatestJobReturnsTheLibrarysMostRecentJob() {
    UUID libraryId = UUID.randomUUID();
    var job = new IndexingJob(JobStatus.COMPLETED);
    when(indexingJobRepository.findTopByLibraryIdOrderByStartedAtDesc(libraryId))
        .thenReturn(Optional.of(job));

    assertThat(service.getLatestJob(libraryId)).contains(job);
  }

  @Test
  void isJobRunningReflectsOnlyTheGivenLibrary() {
    // #478: concurrency is per library - this must never ask about the whole indexing_jobs table.
    UUID libraryId = UUID.randomUUID();
    when(indexingJobRepository.existsByStatusAndLibraryId(JobStatus.RUNNING, libraryId))
        .thenReturn(true);

    assertThat(service.isJobRunning(libraryId)).isTrue();
  }

  @Test
  void isJobRunningReturnsFalseWhenTheLibraryHasNoRunningJob() {
    UUID libraryId = UUID.randomUUID();
    when(indexingJobRepository.existsByStatusAndLibraryId(JobStatus.RUNNING, libraryId))
        .thenReturn(false);

    assertThat(service.isJobRunning(libraryId)).isFalse();
  }
}
