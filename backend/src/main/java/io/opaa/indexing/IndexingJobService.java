package io.opaa.indexing;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class IndexingJobService {

  private final IndexingJobRepository indexingJobRepository;

  public IndexingJobService(IndexingJobRepository indexingJobRepository) {
    this.indexingJobRepository = indexingJobRepository;
  }

  @Transactional
  public IndexingJob startJob(UUID libraryId) {
    var job = new IndexingJob(JobStatus.RUNNING);
    job.setLibraryId(libraryId);
    return indexingJobRepository.save(job);
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
