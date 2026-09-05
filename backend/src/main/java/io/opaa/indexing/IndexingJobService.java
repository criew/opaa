package io.opaa.indexing;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.common.ConflictException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class IndexingJobService {

  /**
   * Error message a run that was still {@link JobStatus#RUNNING} at the previous application
   * startup gets - see {@link #recoverJobsOrphanedByRestart}.
   */
  static final String RESTART_ABORTED_MESSAGE = "Durch Neustart abgebrochen";

  /**
   * Error message a run gets when it is still {@link JobStatus#RUNNING} well past {@code
   * IndexingProperties#staleJobTimeout} while the application keeps running - see {@link
   * #recoverStaleJobs}.
   */
  static final String STALE_RUN_MESSAGE =
      "Indizierungslauf abgebrochen: verwaister Lauf (Zeitüberschreitung)";

  /**
   * How many runs are kept per library: older runs, together with their {@link IndexingRunEvent}s,
   * are pruned by {@link #pruneOldRuns} whenever a new run starts.
   */
  static final int MAX_RETAINED_RUNS_PER_LIBRARY = 10;

  private final IndexingJobRepository indexingJobRepository;

  public IndexingJobService(IndexingJobRepository indexingJobRepository) {
    this.indexingJobRepository = indexingJobRepository;
  }

  /**
   * Starts a run for {@code libraryId} in an explicit {@link IndexingRunMode} (ADR-0023,
   * Entscheidung 4) - there is no default mode, the caller resolves it from the executor's own
   * declaration. Only one running job is allowed per library ({@code
   * uk_indexing_jobs_library_running}): a concurrent second start fails with a 409 here.
   */
  @Transactional
  public IndexingJob startJob(
      UUID libraryId, UUID organizationId, JobTriggerSource triggeredBy, IndexingRunMode runMode) {
    return doStartJob(libraryId, organizationId, triggeredBy, runMode);
  }

  /**
   * The actual work behind both {@code startJob} overloads above - deliberately a private helper
   * both public, {@code @Transactional} entry points delegate to, rather than one overload calling
   * the other directly: a same-class call to another method on {@code this} never goes through the
   * Spring AOP proxy that applies {@code @Transactional}, since the proxy only intercepts calls
   * arriving from outside the bean.
   */
  private IndexingJob doStartJob(
      UUID libraryId, UUID organizationId, JobTriggerSource triggeredBy, IndexingRunMode runMode) {
    var job = new IndexingJob(JobStatus.RUNNING);
    job.setLibraryId(libraryId);
    job.setOrganizationId(organizationId);
    job.setTriggeredBy(triggeredBy);
    job.setRunMode(runMode);
    IndexingJob saved;
    try {
      saved = indexingJobRepository.saveAndFlush(job);
    } catch (DataIntegrityViolationException ex) {
      throw new ConflictException("Für diese Bibliothek läuft bereits ein Indizierungslauf", ex);
    }
    pruneOldRuns(libraryId);
    return saved;
  }

  /**
   * Keeps only the {@value #MAX_RETAINED_RUNS_PER_LIBRARY} most recent runs for {@code libraryId};
   * {@code fk_indexing_run_events_job}'s {@code ON DELETE CASCADE} removes each pruned run's events
   * with it. Called from {@link #startJob}, so the new run counts among the retained ones. The most
   * recent run that assessed its listing is always kept, even beyond the cap - the library's
   * incomplete-listing warning hangs on that row.
   */
  private void pruneOldRuns(UUID libraryId) {
    List<IndexingJob> runs = indexingJobRepository.findByLibraryIdOrderByStartedAtDesc(libraryId);
    if (runs.size() <= MAX_RETAINED_RUNS_PER_LIBRARY) {
      return;
    }
    Optional<UUID> latestAssessmentId =
        runs.stream()
            .filter(run -> run.getListingComplete() != null)
            .findFirst()
            .map(IndexingJob::getId);
    List<UUID> staleIds =
        runs.subList(MAX_RETAINED_RUNS_PER_LIBRARY, runs.size()).stream()
            .map(IndexingJob::getId)
            .filter(id -> latestAssessmentId.map(kept -> !kept.equals(id)).orElse(true))
            .toList();
    if (staleIds.isEmpty()) {
      return;
    }
    indexingJobRepository.deleteAllByIdInBatch(staleIds);
  }

  /**
   * Completes {@code jobId} - unless it is no longer {@link JobStatus#RUNNING}. Without that guard,
   * a job the stale-run sweep or startup recovery already failed - while its own executor thread,
   * unaware, kept running regardless - would have this call silently flip the row back from {@code
   * FAILED} to {@code COMPLETED} once that thread finally finishes. See {@link
   * IndexingJobRepository#completeIfRunning}'s Javadoc for the conditional-update mechanics.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void completeJob(
      UUID jobId,
      int documentsProcessed,
      int documentsFailed,
      int documentsSkipped,
      int documentsIndexedTotal) {
    int updated =
        indexingJobRepository.completeIfRunning(
            jobId,
            documentsProcessed,
            documentsFailed,
            documentsSkipped,
            documentsIndexedTotal,
            Instant.now());
    requireJobExistedIfNoRowsUpdated(jobId, updated);
  }

  /**
   * Fails {@code jobId} - unless it is no longer {@link JobStatus#RUNNING}, the same reasoning and
   * guard as {@link #completeJob}.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failJob(UUID jobId, String errorMessage) {
    int updated = indexingJobRepository.failIfRunning(jobId, errorMessage, Instant.now());
    requireJobExistedIfNoRowsUpdated(jobId, updated);
  }

  /**
   * {@link #completeJob} and {@link #failJob} both use a conditional {@code UPDATE ... WHERE status
   * = RUNNING}, so zero rows updated is ambiguous: either {@code jobId} does not exist at all
   * (throws {@link IllegalArgumentException}), or it exists but is no longer {@code RUNNING}
   * (already recovered - a legitimate race, not an error). This distinguishes the two with one
   * extra existence check, made only in the zero-rows case.
   */
  private void requireJobExistedIfNoRowsUpdated(UUID jobId, int updatedRows) {
    if (updatedRows == 0 && !indexingJobRepository.existsById(jobId)) {
      throw new IllegalArgumentException("Job not found: " + jobId);
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void setTotalDocuments(UUID jobId, int totalDocuments) {
    var job =
        indexingJobRepository
            .findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
    job.setDocumentsTotal(totalDocuments);
    indexingJobRepository.save(job);
  }

  /**
   * Reports progress and touches {@link IndexingJob#getLastProgressAt()}, the heartbeat {@link
   * #recoverStaleJobs} compares against. Called once per file or entry an active run processes, so
   * a genuinely active run's heartbeat never falls behind however long the run grows. A no-op once
   * the job is no longer {@link JobStatus#RUNNING}.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void updateProgress(
      UUID jobId,
      int documentsProcessed,
      int documentsFailed,
      int documentsSkipped,
      int documentsIndexedTotal) {
    var job =
        indexingJobRepository
            .findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
    if (job.getStatus() != JobStatus.RUNNING) {
      return;
    }
    job.setDocumentsProcessed(documentsProcessed);
    job.setDocumentsFailed(documentsFailed);
    job.setDocumentsSkipped(documentsSkipped);
    job.setDocumentsIndexedTotal(documentsIndexedTotal);
    job.setLastProgressAt(Instant.now());
    indexingJobRepository.save(job);
  }

  /**
   * Records the run's cost figures and its incomplete flag - called once by an executor right
   * before {@link #completeJob}, so a COMPLETED row either carries them or never will. A no-op once
   * the job is no longer {@link JobStatus#RUNNING}, like {@link #updateProgress}.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordRunMetrics(UUID jobId, IndexingRunCost metrics) {
    var job =
        indexingJobRepository
            .findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
    if (job.getStatus() != JobStatus.RUNNING) {
      return;
    }
    job.applyMetrics(metrics);
    indexingJobRepository.save(job);
  }

  /**
   * Records whether {@code jobId}'s run assessed its source listing as complete and, if not, which
   * containers (Confluence spaces) it could not read - called at most once per run, by the run
   * frame of every fully listing connector whose run was not cut short. A no-op once the job is no
   * longer {@link JobStatus#RUNNING}, like {@link #recordRunMetrics}.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordListingAssessment(UUID jobId, boolean complete, List<String> unreadableKeys) {
    var job =
        indexingJobRepository
            .findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
    if (job.getStatus() != JobStatus.RUNNING) {
      return;
    }
    job.recordListingAssessment(complete, unreadableKeys);
    indexingJobRepository.save(job);
  }

  /**
   * The most recent run for {@code libraryId} that assessed its source listing, or empty while none
   * has. This - not the most recent run overall - is what the library's warning about an incomplete
   * listing hangs on; see {@link
   * IndexingJobRepository#findTopByLibraryIdAndOrganizationIdAndListingCompleteIsNotNullOrderByStartedAtDesc}.
   */
  @Transactional(readOnly = true)
  public Optional<IndexingJob> getLatestListingAssessment(UUID libraryId, UUID organizationId) {
    return indexingJobRepository
        .findTopByLibraryIdAndOrganizationIdAndListingCompleteIsNotNullOrderByStartedAtDesc(
            libraryId, organizationId);
  }

  /**
   * Records how many further {@link IndexingRunEvent}s {@code jobId}'s run recorded beyond {@link
   * IndexingRunEventRecorder#MAX_EVENTS_PER_RUN} - a no-op call (0) is never made; every executor
   * only calls this once, at the end of a run, when {@code
   * IndexingRunEventRecorder#overflowCount()} is actually positive.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordEventsTruncated(UUID jobId, int truncatedCount) {
    var job =
        indexingJobRepository
            .findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
    job.setEventsTruncatedCount(truncatedCount);
    indexingJobRepository.save(job);
  }

  /**
   * The most recent run for {@code libraryId} within {@code organizationId}, or empty if it never
   * ran. Used both to answer the per-library status endpoint and, indirectly, by {@link
   * #isJobRunning(UUID, UUID)}. {@code organizationId} is a second, independent guard on top of
   * {@code libraryId} - see {@link
   * IndexingJobRepository#findTopByLibraryIdAndOrganizationIdOrderByStartedAtDesc}'s Javadoc.
   */
  @Transactional(readOnly = true)
  public Optional<IndexingJob> getLatestJob(UUID libraryId, UUID organizationId) {
    return indexingJobRepository.findTopByLibraryIdAndOrganizationIdOrderByStartedAtDesc(
        libraryId, organizationId);
  }

  /**
   * The last {@value #MAX_RETAINED_RUNS_PER_LIBRARY} runs for {@code libraryId}, newest first.
   * {@link #pruneOldRuns} keeps at most that many going forward, but an older library can still
   * carry more rows until its next run prunes them - the bound on this query is {@link
   * IndexingJobRepository#findTop10ByLibraryIdAndOrganizationIdOrderByStartedAtDesc}.
   */
  @Transactional(readOnly = true)
  public List<IndexingJob> getRecentJobs(UUID libraryId, UUID organizationId) {
    return indexingJobRepository.findTop10ByLibraryIdAndOrganizationIdOrderByStartedAtDesc(
        libraryId, organizationId);
  }

  /**
   * Whether a run for {@code libraryId} within {@code organizationId} is currently in progress -
   * one running job per library, not one running job for the whole application. {@code
   * organizationId} is a second, independent guard on top of {@code libraryId} - see {@link
   * IndexingJobRepository#findTopByLibraryIdAndOrganizationIdOrderByStartedAtDesc}'s Javadoc.
   */
  @Transactional(readOnly = true)
  public boolean isJobRunning(UUID libraryId, UUID organizationId) {
    return indexingJobRepository.existsByStatusAndLibraryIdAndOrganizationId(
        JobStatus.RUNNING, libraryId, organizationId);
  }

  /**
   * Whether {@code libraryId}'s two most recent {@link JobTriggerSource#SCHEDULED} runs both ended
   * {@link JobStatus#FAILED} - {@code false} when fewer than two scheduled runs exist yet. A
   * currently {@link JobStatus#RUNNING} scheduled run counts as not-failed here, like every other
   * non-FAILED status - the banner only fires once a retry has actually failed again.
   */
  @Transactional(readOnly = true)
  public boolean lastScheduledRunsFailed(UUID libraryId, UUID organizationId) {
    List<IndexingJob> recentScheduled =
        indexingJobRepository
            .findTop2ByLibraryIdAndOrganizationIdAndTriggeredByOrderByStartedAtDesc(
                libraryId, organizationId, JobTriggerSource.SCHEDULED);
    return recentScheduled.size() == 2
        && recentScheduled.stream().allMatch(job -> job.getStatus() == JobStatus.FAILED);
  }

  /**
   * Fails every row still {@link JobStatus#RUNNING} from a previous application run. Called once at
   * startup: a fresh JVM cannot be running the {@code @Async} task such a row refers to, so no age
   * threshold applies, unlike {@link #recoverStaleJobs}. Assumes exactly one backend process; under
   * genuine multi-instance operation this would abort another instance's live jobs (ADR-0021).
   *
   * @return the number of rows recovered
   */
  @Transactional
  public int recoverJobsOrphanedByRestart() {
    return indexingJobRepository.failAllRunningJobs(RESTART_ABORTED_MESSAGE, Instant.now());
  }

  /**
   * Fails every row still {@link JobStatus#RUNNING} whose {@link IndexingJob#getLastProgressAt()}
   * heartbeat is older than {@code staleAfter} - the only guard against a run orphaned without a
   * restart. Compares against {@code lastProgressAt}, not {@code startedAt}, since a large corpus
   * can genuinely exceed {@code staleAfter} in wall-clock age while still processing files.
   *
   * @return the number of rows recovered
   */
  @Transactional
  public int recoverStaleJobs(Duration staleAfter) {
    Instant cutoff = Instant.now().minus(staleAfter);
    return indexingJobRepository.failStaleRunningJobs(STALE_RUN_MESSAGE, cutoff, Instant.now());
  }
}
