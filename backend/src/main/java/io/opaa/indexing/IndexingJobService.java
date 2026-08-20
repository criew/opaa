package io.opaa.indexing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

public class IndexingJobService {

  /**
   * Error message a run that was still {@link JobStatus#RUNNING} at the previous application
   * startup gets (#501) - see {@link #recoverJobsOrphanedByRestart}.
   */
  static final String RESTART_ABORTED_MESSAGE = "Durch Neustart abgebrochen";

  /**
   * Error message a run gets when it is still {@link JobStatus#RUNNING} well past {@code
   * IndexingProperties#staleJobTimeout} while the application keeps running (#501) - see {@link
   * #recoverStaleJobs}.
   */
  static final String STALE_RUN_MESSAGE =
      "Indizierungslauf abgebrochen: verwaister Lauf (Zeitüberschreitung)";

  /**
   * How many runs are kept per library (#513, Umfangserweiterung - Maintainer-Ergaenzung
   * 20.08.2026): older runs, together with their {@link IndexingRunEvent}s, are pruned by {@link
   * #pruneOldRuns} whenever a new run starts.
   */
  static final int MAX_RETAINED_RUNS_PER_LIBRARY = 10;

  private final IndexingJobRepository indexingJobRepository;

  public IndexingJobService(IndexingJobRepository indexingJobRepository) {
    this.indexingJobRepository = indexingJobRepository;
  }

  /**
   * Starts a new {@link JobStatus#RUNNING} run for {@code libraryId}.
   *
   * <p><b>#500 review, finding 3 (TOCTOU).</b> {@code DocumentIndexingService#triggerIndexing}'s
   * own {@link #isJobRunning(UUID)} check and this insert are two separate statements with no lock
   * between them, so two concurrent triggers for the same library can both pass that check before
   * either has inserted its row. The database closes that gap: {@code
   * uk_indexing_jobs_library_running} (migration 028) is a partial unique index on {@code
   * (library_id) WHERE status = 'RUNNING'}, so at most one RUNNING row per library can ever exist.
   * {@link IndexingJobRepository#saveAndFlush} - not plain {@code save} - forces the insert (and
   * therefore the constraint check) to happen synchronously here, inside this method's own
   * transaction, rather than being deferred to a later flush the caller could not catch. The loser
   * of the race gets the exact same 409 the in-memory check already produces for the same-thread
   * case, so callers cannot tell which of the two guards actually caught it.
   */
  @Transactional
  public IndexingJob startJob(UUID libraryId) {
    var job = new IndexingJob(JobStatus.RUNNING);
    job.setLibraryId(libraryId);
    IndexingJob saved;
    try {
      saved = indexingJobRepository.saveAndFlush(job);
    } catch (DataIntegrityViolationException ex) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Für diese Bibliothek läuft bereits ein Indizierungslauf", ex);
    }
    pruneOldRuns(libraryId);
    return saved;
  }

  /**
   * Keeps only the {@value #MAX_RETAINED_RUNS_PER_LIBRARY} most recent runs for {@code libraryId}
   * (#513, Umfangserweiterung), deleting every older one - {@code fk_indexing_run_events_job}'s
   * {@code ON DELETE CASCADE} (migration 037) removes each pruned run's own {@link
   * IndexingRunEvent}s along with it, so this method never needs to know about the event table at
   * all. Called from {@link #startJob} so the newly started run is always counted among the
   * retained ones, rather than pruning happening only on completion and briefly allowing 11.
   */
  private void pruneOldRuns(UUID libraryId) {
    List<IndexingJob> runs = indexingJobRepository.findByLibraryIdOrderByStartedAtDesc(libraryId);
    if (runs.size() <= MAX_RETAINED_RUNS_PER_LIBRARY) {
      return;
    }
    List<UUID> staleIds =
        runs.subList(MAX_RETAINED_RUNS_PER_LIBRARY, runs.size()).stream()
            .map(IndexingJob::getId)
            .toList();
    indexingJobRepository.deleteAllByIdInBatch(staleIds);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void completeJob(
      UUID jobId,
      int documentsProcessed,
      int documentsFailed,
      int documentsSkipped,
      int documentsIndexedTotal) {
    var job =
        indexingJobRepository
            .findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
    job.setStatus(JobStatus.COMPLETED);
    job.setDocumentsProcessed(documentsProcessed);
    job.setDocumentsFailed(documentsFailed);
    job.setDocumentsSkipped(documentsSkipped);
    job.setDocumentsIndexedTotal(documentsIndexedTotal);
    job.setCompletedAt(Instant.now());
    indexingJobRepository.save(job);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failJob(UUID jobId, String errorMessage) {
    var job =
        indexingJobRepository
            .findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
    job.setStatus(JobStatus.FAILED);
    job.setErrorMessage(errorMessage);
    job.setCompletedAt(Instant.now());
    indexingJobRepository.save(job);
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
    job.setDocumentsProcessed(documentsProcessed);
    job.setDocumentsFailed(documentsFailed);
    job.setDocumentsSkipped(documentsSkipped);
    job.setDocumentsIndexedTotal(documentsIndexedTotal);
    indexingJobRepository.save(job);
  }

  /**
   * Records how many further {@link IndexingRunEvent}s {@code jobId}'s run recorded beyond {@link
   * IndexingRunEventRecorder#MAX_EVENTS_PER_RUN} (#513) - a no-op call (0) is never made; every
   * executor only calls this once, at the end of a run, when {@code
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
   * The most recent run for {@code libraryId}, or empty if it never ran. Used both to answer the
   * per-library status endpoint and, indirectly, by {@link #isJobRunning(UUID)} (#478).
   */
  @Transactional(readOnly = true)
  public Optional<IndexingJob> getLatestJob(UUID libraryId) {
    return indexingJobRepository.findTopByLibraryIdOrderByStartedAtDesc(libraryId);
  }

  /**
   * The last {@value #MAX_RETAINED_RUNS_PER_LIBRARY} runs for {@code libraryId}, newest first
   * (#513). {@link #pruneOldRuns} keeps at most that many rows for a library going forward, but a
   * Bestandsbibliothek can still carry far more historical rows predating this issue's retention
   * pruning until its next run prunes them - {@link
   * IndexingJobRepository#findTop10ByLibraryIdOrderByStartedAtDesc}, not the unbounded {@code
   * findByLibraryIdOrderByStartedAtDesc} {@link #pruneOldRuns} itself uses, is what actually bounds
   * this query (PR #604 review, finding 3).
   */
  @Transactional(readOnly = true)
  public List<IndexingJob> getRecentJobs(UUID libraryId) {
    return indexingJobRepository.findTop10ByLibraryIdOrderByStartedAtDesc(libraryId);
  }

  /**
   * Whether a run for {@code libraryId} is currently in progress (#478: one running job per
   * library, not one running job for the whole application - runs of different libraries no longer
   * block each other).
   */
  @Transactional(readOnly = true)
  public boolean isJobRunning(UUID libraryId) {
    return indexingJobRepository.existsByStatusAndLibraryId(JobStatus.RUNNING, libraryId);
  }

  /**
   * Fails every row still {@link JobStatus#RUNNING} from a previous application run (#501). Called
   * once at startup ({@code IndexingJobRecoveryScheduler#recoverOnStartup}): a fresh JVM cannot be
   * running the {@code @Async} task any such row refers to, so every one of them is orphaned by
   * definition, not merely suspected of it - unlike {@link #recoverStaleJobs}, no age threshold
   * applies here.
   *
   * @return the number of rows recovered
   */
  @Transactional
  public int recoverJobsOrphanedByRestart() {
    return indexingJobRepository.failAllRunningJobs(RESTART_ABORTED_MESSAGE, Instant.now());
  }

  /**
   * Fails every row still {@link JobStatus#RUNNING} whose {@link IndexingJob#getStartedAt()} is
   * older than {@code staleAfter} (#501). Called periodically while the application keeps running
   * ({@code IndexingJobRecoveryScheduler#recoverStaleRunningJobs}) - the only guard against a run
   * orphaned without a restart, e.g. a task a full queue silently dropped before this issue's
   * {@code AbortPolicy} change, or one truly hung well past any run this library would normally
   * take.
   *
   * @return the number of rows recovered
   */
  @Transactional
  public int recoverStaleJobs(Duration staleAfter) {
    Instant cutoff = Instant.now().minus(staleAfter);
    return indexingJobRepository.failStaleRunningJobs(STALE_RUN_MESSAGE, cutoff, Instant.now());
  }
}
