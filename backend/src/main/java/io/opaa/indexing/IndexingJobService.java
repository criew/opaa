package io.opaa.indexing;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class IndexingJobService {

  private final IndexingJobRepository indexingJobRepository;

  public IndexingJobService(IndexingJobRepository indexingJobRepository) {
    this.indexingJobRepository = indexingJobRepository;
  }

  @Transactional
  public IndexingJob startJob() {
    var job = new IndexingJob(JobStatus.RUNNING);
    return indexingJobRepository.save(job);
  }

  @Transactional
  public void completeJob(UUID jobId, int documentsProcessed, int documentsFailed) {
    var job =
        indexingJobRepository
            .findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
    job.setStatus(JobStatus.COMPLETED);
    job.setDocumentsProcessed(documentsProcessed);
    job.setDocumentsFailed(documentsFailed);
    job.setCompletedAt(Instant.now());
    indexingJobRepository.save(job);
  }

  @Transactional
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

  @Transactional(readOnly = true)
  public Optional<IndexingJob> getLatestJob() {
    return indexingJobRepository.findTopByOrderByStartedAtDesc();
  }
}
