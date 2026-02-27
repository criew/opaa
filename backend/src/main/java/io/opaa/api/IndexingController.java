package io.opaa.api;

import io.opaa.api.dto.IndexingStatus;
import io.opaa.api.dto.IndexingStatusResponse;
import io.opaa.indexing.DocumentIndexingService;
import io.opaa.indexing.IndexingAlreadyRunningException;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.JobStatus;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!mock")
@RestController
@RequestMapping("/api/v1/indexing")
public class IndexingController {

  private final DocumentIndexingService documentIndexingService;
  private final IndexingJobService indexingJobService;

  public IndexingController(
      DocumentIndexingService documentIndexingService, IndexingJobService indexingJobService) {
    this.documentIndexingService = documentIndexingService;
    this.indexingJobService = indexingJobService;
  }

  @PostMapping("/trigger")
  public ResponseEntity<IndexingStatusResponse> triggerIndexing() {
    IndexingJob job = documentIndexingService.triggerIndexing();
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(job));
  }

  @ExceptionHandler(IndexingAlreadyRunningException.class)
  public ResponseEntity<IndexingStatusResponse> handleAlreadyRunning(
      IndexingAlreadyRunningException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new IndexingStatusResponse(
                IndexingStatus.RUNNING, 0, 0, 0, ex.getMessage(), Instant.now()));
  }

  @GetMapping("/status")
  public IndexingStatusResponse getIndexingStatus() {
    return indexingJobService
        .getLatestJob()
        .map(this::toResponse)
        .orElse(
            new IndexingStatusResponse(
                IndexingStatus.IDLE, 0, 0, 0, "No indexing job found", Instant.now()));
  }

  private IndexingStatusResponse toResponse(IndexingJob job) {
    IndexingStatus status = mapStatus(job.getStatus());
    String message =
        switch (job.getStatus()) {
          case RUNNING -> "Indexing in progress";
          case COMPLETED ->
              "Indexing completed: "
                  + job.getDocumentsProcessed()
                  + " processed, "
                  + job.getDocumentsSkipped()
                  + " skipped, "
                  + job.getDocumentsFailed()
                  + " failed";
          case FAILED -> "Indexing failed: " + job.getErrorMessage();
        };
    return new IndexingStatusResponse(
        status,
        job.getDocumentsProcessed(),
        job.getDocumentsTotal(),
        job.getDocumentsSkipped(),
        message,
        job.getCompletedAt() != null ? job.getCompletedAt() : job.getStartedAt());
  }

  private IndexingStatus mapStatus(JobStatus jobStatus) {
    return switch (jobStatus) {
      case RUNNING -> IndexingStatus.RUNNING;
      case COMPLETED -> IndexingStatus.COMPLETED;
      case FAILED -> IndexingStatus.FAILED;
    };
  }
}
