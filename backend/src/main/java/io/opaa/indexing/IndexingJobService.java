package io.opaa.indexing;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

public class IndexingJobService {

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
    try {
      return indexingJobRepository.saveAndFlush(job);
    } catch (DataIntegrityViolationException ex) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Fuer diese Bibliothek laeuft bereits ein Indizierungslauf", ex);
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void completeJob(
      UUID jobId, int documentsProcessed, int documentsFailed, int documentsSkipped) {
    var job =
        indexingJobRepository
            .findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
    job.setStatus(JobStatus.COMPLETED);
    job.setDocumentsProcessed(documentsProcessed);
    job.setDocumentsFailed(documentsFailed);
    job.setDocumentsSkipped(documentsSkipped);
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
      UUID jobId, int documentsProcessed, int documentsFailed, int documentsSkipped) {
    var job =
        indexingJobRepository
            .findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
    job.setDocumentsProcessed(documentsProcessed);
    job.setDocumentsFailed(documentsFailed);
    job.setDocumentsSkipped(documentsSkipped);
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
   * Whether a run for {@code libraryId} is currently in progress (#478: one running job per
   * library, not one running job for the whole application - runs of different libraries no longer
   * block each other).
   */
  @Transactional(readOnly = true)
  public boolean isJobRunning(UUID libraryId) {
    return indexingJobRepository.existsByStatusAndLibraryId(JobStatus.RUNNING, libraryId);
  }
}
