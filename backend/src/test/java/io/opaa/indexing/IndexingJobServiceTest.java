package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.common.ConflictException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

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

    IndexingJob result =
        service.startJob(
            UUID.randomUUID(), UUID.randomUUID(), JobTriggerSource.MANUAL, IndexingRunMode.FULL);

    assertThat(result.getStatus()).isEqualTo(JobStatus.RUNNING);
    assertThat(result.getStartedAt()).isNotNull();
  }

  @Test
  void recordRunMetricsWritesTheCostFiguresOntoARunningJobOnly() {
    // the figures land on the row while it is RUNNING; a job the stale sweep already failed
    // keeps its state - the same conditional guard updateProgress has.
    var running = new IndexingJob(JobStatus.RUNNING);
    var failed = new IndexingJob(JobStatus.FAILED);
    when(indexingJobRepository.findById(running.getId())).thenReturn(Optional.of(running));
    when(indexingJobRepository.findById(failed.getId())).thenReturn(Optional.of(failed));
    IndexingRunCost cost = new IndexingRunCost(1842, 3, 95_000L, 120, 800, 2, true);

    service.recordRunMetrics(running.getId(), cost);
    service.recordRunMetrics(failed.getId(), cost);

    assertThat(running.getMetrics()).isEqualTo(cost);
    assertThat(running.isIncomplete()).isTrue();
    assertThat(failed.getMetrics()).isNull();
    assertThat(failed.isIncomplete()).isFalse();
    verify(indexingJobRepository, times(1)).save(running);
    verify(indexingJobRepository, never()).save(failed);
  }

  @Test
  void recordListingAssessmentWritesTheVerdictOntoARunningJobOnly() {
    // the verdict lands on the row while it is RUNNING; a job the stale sweep already
    // failed keeps its state - the same conditional guard recordRunMetrics has.
    var running = new IndexingJob(JobStatus.RUNNING);
    var failed = new IndexingJob(JobStatus.FAILED);
    when(indexingJobRepository.findById(running.getId())).thenReturn(Optional.of(running));
    when(indexingJobRepository.findById(failed.getId())).thenReturn(Optional.of(failed));

    service.recordListingAssessment(running.getId(), false, List.of("SEC", "IT"));
    service.recordListingAssessment(failed.getId(), false, List.of("SEC"));

    assertThat(running.getListingComplete()).isFalse();
    assertThat(running.getUnreadableSpaceKeys()).containsExactly("SEC", "IT");
    assertThat(failed.getListingComplete()).isNull();
    assertThat(failed.getUnreadableSpaceKeys()).isEmpty();
    verify(indexingJobRepository, times(1)).save(any());
  }

  @Test
  void aCompleteListingAssessmentCarriesNoKeys() {
    var running = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobRepository.findById(running.getId())).thenReturn(Optional.of(running));

    service.recordListingAssessment(running.getId(), true, List.of());

    assertThat(running.getListingComplete()).isTrue();
    assertThat(running.getUnreadableSpaceKeys()).isEmpty();
  }

  @Test
  void pruningKeepsTheMostRecentListingAssessmentBeyondTheCap() {
    // the library's incomplete-listing warning hangs on the most recent assessing run - a
    // burst of webhook runs (which never assess) must not prune that row away.
    UUID libraryId = UUID.randomUUID();
    List<IndexingJob> runs = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      runs.add(new IndexingJob(JobStatus.COMPLETED));
    }
    runs.get(10).recordListingAssessment(false, List.of("SEC"));
    when(indexingJobRepository.findByLibraryIdOrderByStartedAtDesc(libraryId)).thenReturn(runs);
    when(indexingJobRepository.saveAndFlush(any(IndexingJob.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.startJob(libraryId, UUID.randomUUID(), JobTriggerSource.MANUAL, IndexingRunMode.FULL);

    verify(indexingJobRepository).deleteAllByIdInBatch(List.of(runs.get(11).getId()));
  }

  @Test
  void startJobRecordsTheTargetLibrary() {
    // The indexing job records its target library.
    UUID libraryId = UUID.randomUUID();
    when(indexingJobRepository.saveAndFlush(any(IndexingJob.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    IndexingJob result =
        service.startJob(
            libraryId, UUID.randomUUID(), JobTriggerSource.MANUAL, IndexingRunMode.FULL);

    assertThat(result.getLibraryId()).isEqualTo(libraryId);
  }

  @Test
  void startJobRecordsTheOrganization() {
    // The indexing job records the organization it belongs to.
    UUID organizationId = UUID.randomUUID();
    when(indexingJobRepository.saveAndFlush(any(IndexingJob.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    IndexingJob result =
        service.startJob(
            UUID.randomUUID(), organizationId, JobTriggerSource.MANUAL, IndexingRunMode.FULL);

    assertThat(result.getOrganizationId()).isEqualTo(organizationId);
  }

  @Test
  void startJobMapsAConstraintViolationOnTheUniqueRunningIndexTo409() {
    // DocumentIndexingService's own isJobRunning check and this
    // insert are not atomic - a concurrent second trigger for the same library can pass that check
    // before either has inserted, so the database's partial unique index
    // (uk_indexing_jobs_library_running, migration 028) is the guard that actually always holds.
    UUID libraryId = UUID.randomUUID();
    when(indexingJobRepository.saveAndFlush(any(IndexingJob.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));

    assertThatThrownBy(
            () ->
                service.startJob(
                    libraryId, UUID.randomUUID(), JobTriggerSource.MANUAL, IndexingRunMode.FULL))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Für diese Bibliothek läuft bereits ein Indizierungslauf");
  }

  @Test
  void completeJobUpdatesTheRowConditionallyOnStillBeingRunning() {
    UUID jobId = UUID.randomUUID();
    when(indexingJobRepository.completeIfRunning(
            eq(jobId), eq(10), eq(2), eq(5), eq(12), any(Instant.class)))
        .thenReturn(1);

    service.completeJob(jobId, 10, 2, 5, 12);

    verify(indexingJobRepository)
        .completeIfRunning(eq(jobId), eq(10), eq(2), eq(5), eq(12), any(Instant.class));
    verify(indexingJobRepository, never()).existsById(any());
  }

  @Test
  void failJobUpdatesTheRowConditionallyOnStillBeingRunning() {
    UUID jobId = UUID.randomUUID();
    when(indexingJobRepository.failIfRunning(
            eq(jobId), eq("Something went wrong"), any(Instant.class)))
        .thenReturn(1);

    service.failJob(jobId, "Something went wrong");

    verify(indexingJobRepository)
        .failIfRunning(eq(jobId), eq("Something went wrong"), any(Instant.class));
    verify(indexingJobRepository, never()).existsById(any());
  }

  @Test
  void completeJobThrowsForUnknownJob() {
    UUID jobId = UUID.randomUUID();
    when(indexingJobRepository.completeIfRunning(
            eq(jobId), anyInt(), anyInt(), anyInt(), anyInt(), any(Instant.class)))
        .thenReturn(0);
    when(indexingJobRepository.existsById(jobId)).thenReturn(false);

    assertThatThrownBy(() -> service.completeJob(jobId, 0, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void failJobThrowsForUnknownJob() {
    UUID jobId = UUID.randomUUID();
    when(indexingJobRepository.failIfRunning(eq(jobId), anyString(), any(Instant.class)))
        .thenReturn(0);
    when(indexingJobRepository.existsById(jobId)).thenReturn(false);

    assertThatThrownBy(() -> service.failJob(jobId, "boom"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * a job the stale-run sweep already failed must not have its status flipped back to COMPLETED
   * once its (unaware) executor thread finally calls this - the conditional {@code UPDATE ... WHERE
   * status = RUNNING} in {@code IndexingJobRepository#completeIfRunning} affects 0 rows in that
   * case, and the row still exists (just no longer RUNNING), so this must complete silently instead
   * of throwing.
   */
  @Test
  void completeJobDoesNothingWhenTheJobIsNoLongerRunning() {
    UUID jobId = UUID.randomUUID();
    when(indexingJobRepository.completeIfRunning(
            eq(jobId), anyInt(), anyInt(), anyInt(), anyInt(), any(Instant.class)))
        .thenReturn(0);
    when(indexingJobRepository.existsById(jobId)).thenReturn(true);

    service.completeJob(jobId, 1, 0, 0, 1);
  }

  /** Same guard as {@link #completeJobDoesNothingWhenTheJobIsNoLongerRunning}, failure path. */
  @Test
  void failJobDoesNothingWhenTheJobIsNoLongerRunning() {
    UUID jobId = UUID.randomUUID();
    when(indexingJobRepository.failIfRunning(eq(jobId), anyString(), any(Instant.class)))
        .thenReturn(0);
    when(indexingJobRepository.existsById(jobId)).thenReturn(true);

    service.failJob(jobId, "boom");
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
    Instant previousHeartbeat = job.getLastProgressAt();
    when(indexingJobRepository.findById(jobId)).thenReturn(Optional.of(job));
    when(indexingJobRepository.save(any(IndexingJob.class))).thenReturn(job);

    service.updateProgress(jobId, 5, 1, 3, 6);

    assertThat(job.getDocumentsProcessed()).isEqualTo(5);
    assertThat(job.getDocumentsFailed()).isEqualTo(1);
    assertThat(job.getDocumentsSkipped()).isEqualTo(3);
    assertThat(job.getDocumentsIndexedTotal()).isEqualTo(6);
    assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
    assertThat(job.getCompletedAt()).isNull();
    // every progress report is also the heartbeat the stale-run sweep
    // compares against its cutoff.
    assertThat(job.getLastProgressAt()).isNotNull().isAfterOrEqualTo(previousHeartbeat);
  }

  /**
   * once the sweep has already failed a job, its executor thread's further progress reports (it is
   * unaware of the recovery) must not resurrect its counters or heartbeat.
   */
  @Test
  void updateProgressDoesNothingWhenTheJobIsNoLongerRunning() {
    UUID jobId = UUID.randomUUID();
    var job = new IndexingJob(JobStatus.RUNNING);
    job.setStatus(JobStatus.FAILED);
    job.setErrorMessage("Indizierungslauf abgebrochen: verwaister Lauf (Zeitüberschreitung)");
    when(indexingJobRepository.findById(jobId)).thenReturn(Optional.of(job));

    service.updateProgress(jobId, 5, 1, 3, 6);

    assertThat(job.getDocumentsProcessed()).isZero();
    assertThat(job.getErrorMessage())
        .isEqualTo("Indizierungslauf abgebrochen: verwaister Lauf (Zeitüberschreitung)");
    verify(indexingJobRepository, never()).save(any());
  }

  @Test
  void getLatestJobReturnsEmptyWhenTheLibraryNeverRan() {
    UUID libraryId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(indexingJobRepository.findTopByLibraryIdAndOrganizationIdOrderByStartedAtDesc(
            libraryId, organizationId))
        .thenReturn(Optional.empty());

    assertThat(service.getLatestJob(libraryId, organizationId)).isEmpty();
  }

  @Test
  void getLatestJobReturnsTheLibrarysMostRecentJob() {
    UUID libraryId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    var job = new IndexingJob(JobStatus.COMPLETED);
    when(indexingJobRepository.findTopByLibraryIdAndOrganizationIdOrderByStartedAtDesc(
            libraryId, organizationId))
        .thenReturn(Optional.of(job));

    assertThat(service.getLatestJob(libraryId, organizationId)).contains(job);
  }

  /**
   * reproduces the leak the issue describes at the service layer, independently of {@code
   * DocumentIndexingService}'s own library-ownership check. Before the fix (see this test's own
   * two-argument {@code startJob}/{@code getLatestJob} calls being reduced to one argument, and
   * {@code IndexingJobRepository#findTopByLibraryIdOrderByStartedAtDesc} being called without
   * {@code organizationId}), a caller who somehow obtained {@code libraryId} - e.g. through a
   * future code path that does not itself re-check the library's organization - could read
   * organization A's job for organization A's library while asking as organization B: {@code
   * getLatestJob} would answer the same regardless of which organization asked, since {@code
   * libraryId} alone determined the result. With the fix, the repository query is scoped to {@code
   * (libraryId, organizationId)} together, so a mismatched organization id returns nothing, exactly
   * like the library never having run at all.
   */
  @Test
  void getLatestJobDoesNotLeakAJobToACallerFromADifferentOrganization() {
    UUID libraryId = UUID.randomUUID();
    UUID organizationOfTheJob = UUID.randomUUID();
    UUID aDifferentOrganization = UUID.randomUUID();
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobRepository.findTopByLibraryIdAndOrganizationIdOrderByStartedAtDesc(
            libraryId, organizationOfTheJob))
        .thenReturn(Optional.of(job));
    when(indexingJobRepository.findTopByLibraryIdAndOrganizationIdOrderByStartedAtDesc(
            libraryId, aDifferentOrganization))
        .thenReturn(Optional.empty());

    assertThat(service.getLatestJob(libraryId, organizationOfTheJob)).contains(job);
    assertThat(service.getLatestJob(libraryId, aDifferentOrganization)).isEmpty();
  }

  @Test
  void isJobRunningReflectsOnlyTheGivenLibraryAndOrganization() {
    // concurrency is per library - this must never ask about the whole indexing_jobs table.
    UUID libraryId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(indexingJobRepository.existsByStatusAndLibraryIdAndOrganizationId(
            JobStatus.RUNNING, libraryId, organizationId))
        .thenReturn(true);

    assertThat(service.isJobRunning(libraryId, organizationId)).isTrue();
  }

  @Test
  void isJobRunningReturnsFalseWhenTheLibraryHasNoRunningJob() {
    UUID libraryId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(indexingJobRepository.existsByStatusAndLibraryIdAndOrganizationId(
            JobStatus.RUNNING, libraryId, organizationId))
        .thenReturn(false);

    assertThat(service.isJobRunning(libraryId, organizationId)).isFalse();
  }

  /**
   * the same defense-in-depth boundary as {@link
   * #getLatestJobDoesNotLeakAJobToACallerFromADifferentOrganization}, applied to the concurrency
   * check - a run genuinely {@code RUNNING} for {@code libraryId} under one organization must not
   * report as running (and therefore block a trigger) for a different organization asking about the
   * same {@code libraryId}.
   */
  @Test
  void isJobRunningReturnsFalseForTheSameLibraryUnderADifferentOrganization() {
    UUID libraryId = UUID.randomUUID();
    UUID organizationOfTheRunningJob = UUID.randomUUID();
    UUID aDifferentOrganization = UUID.randomUUID();
    when(indexingJobRepository.existsByStatusAndLibraryIdAndOrganizationId(
            JobStatus.RUNNING, libraryId, organizationOfTheRunningJob))
        .thenReturn(true);
    when(indexingJobRepository.existsByStatusAndLibraryIdAndOrganizationId(
            JobStatus.RUNNING, libraryId, aDifferentOrganization))
        .thenReturn(false);

    assertThat(service.isJobRunning(libraryId, organizationOfTheRunningJob)).isTrue();
    assertThat(service.isJobRunning(libraryId, aDifferentOrganization)).isFalse();
  }

  // --- recovery of RUNNING rows orphaned by a restart or a stale/dropped task ---

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
