package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
    when(indexingJobRepository.saveAndFlush(any(IndexingJob.class))).thenReturn(job);

    IndexingJob result = service.startJob(UUID.randomUUID());

    assertThat(result.getStatus()).isEqualTo(JobStatus.RUNNING);
    assertThat(result.getStartedAt()).isNotNull();
  }

  @Test
  void startJobRecordsTheTargetLibrary() {
    // #419 acceptance criteria: the indexing job records its target library.
    UUID libraryId = UUID.randomUUID();
    when(indexingJobRepository.saveAndFlush(any(IndexingJob.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    IndexingJob result = service.startJob(libraryId);

    assertThat(result.getLibraryId()).isEqualTo(libraryId);
  }

  @Test
  void startJobMapsAConstraintViolationOnTheUniqueRunningIndexTo409() {
    // #500 review, finding 3 (TOCTOU): DocumentIndexingService's own isJobRunning check and this
    // insert are not atomic - a concurrent second trigger for the same library can pass that check
    // before either has inserted, so the database's partial unique index
    // (uk_indexing_jobs_library_running, migration 028) is the guard that actually always holds.
    UUID libraryId = UUID.randomUUID();
    when(indexingJobRepository.saveAndFlush(any(IndexingJob.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));

    assertThatThrownBy(() -> service.startJob(libraryId))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT)
        .hasMessageContaining("Für diese Bibliothek läuft bereits ein Indizierungslauf");
  }

  @Test
  void completeJobSetsStatusAndCounts() {
    UUID jobId = UUID.randomUUID();
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobRepository.findById(jobId)).thenReturn(Optional.of(job));
    when(indexingJobRepository.save(any(IndexingJob.class))).thenReturn(job);

    service.completeJob(jobId, 10, 2, 5, 12);

    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(job.getDocumentsProcessed()).isEqualTo(10);
    assertThat(job.getDocumentsFailed()).isEqualTo(2);
    assertThat(job.getDocumentsSkipped()).isEqualTo(5);
    assertThat(job.getDocumentsIndexedTotal()).isEqualTo(12);
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

    assertThatThrownBy(() -> service.completeJob(jobId, 0, 0, 0, 0))
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

    service.updateProgress(jobId, 5, 1, 3, 6);

    assertThat(job.getDocumentsProcessed()).isEqualTo(5);
    assertThat(job.getDocumentsFailed()).isEqualTo(1);
    assertThat(job.getDocumentsSkipped()).isEqualTo(3);
    assertThat(job.getDocumentsIndexedTotal()).isEqualTo(6);
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

  // --- #501: recovery of RUNNING rows orphaned by a restart or a stale/dropped task ---

  @Test
  void recoverJobsOrphanedByRestartFailsEveryRunningRowWithARestartMessage() {
    when(indexingJobRepository.failAllRunningJobs(anyString(), any(Instant.class))).thenReturn(3);

    int recovered = service.recoverJobsOrphanedByRestart();

    assertThat(recovered).isEqualTo(3);
    verify(indexingJobRepository)
        .failAllRunningJobs(eq("Durch Neustart abgebrochen"), any(Instant.class));
  }

  @Test
  void recoverStaleJobsFailsRunningRowsOlderThanTheGivenDuration() {
    when(indexingJobRepository.failStaleRunningJobs(
            anyString(), any(Instant.class), any(Instant.class)))
        .thenReturn(2);

    int recovered = service.recoverStaleJobs(Duration.ofHours(4));

    assertThat(recovered).isEqualTo(2);
    verify(indexingJobRepository)
        .failStaleRunningJobs(
            eq("Indizierungslauf abgebrochen: verwaister Lauf (Zeitüberschreitung)"),
            any(Instant.class),
            any(Instant.class));
  }
}
