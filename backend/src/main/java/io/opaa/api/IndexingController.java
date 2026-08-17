package io.opaa.api;

import io.opaa.api.dto.IndexingStatus;
import io.opaa.api.dto.IndexingStatusResponse;
import io.opaa.api.dto.IndexingTriggerRequest;
import io.opaa.auth.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.indexing.DocumentIndexingService;
import io.opaa.indexing.IndexingAlreadyRunningException;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.JobStatus;
import io.opaa.indexing.UrlIndexingRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/indexing")
public class IndexingController {

  private static final String UNKNOWN_ISSUER = "unknown";

  private final DocumentIndexingService documentIndexingService;
  private final IndexingJobService indexingJobService;
  private final UserService userService;

  public IndexingController(
      DocumentIndexingService documentIndexingService,
      IndexingJobService indexingJobService,
      UserService userService) {
    this.documentIndexingService = documentIndexingService;
    this.indexingJobService = indexingJobService;
    this.userService = userService;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/trigger")
  public ResponseEntity<IndexingStatusResponse> triggerIndexing(
      @RequestBody IndexingTriggerRequest request, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    boolean systemAdmin = currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN;
    IndexingJob job;
    if (request.getUrl() != null && !request.getUrl().toString().isBlank()) {
      job =
          documentIndexingService.triggerUrlIndexing(
              new UrlIndexingRequest(
                  request.getUrl().toString(),
                  request.getProxy(),
                  request.getCredentials(),
                  Boolean.TRUE.equals(request.getInsecureSsl())),
              request.getLibraryId(),
              currentUser.getId(),
              systemAdmin);
    } else {
      job =
          documentIndexingService.triggerIndexing(
              request.getLibraryId(), currentUser.getId(), systemAdmin);
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(job));
  }

  @ExceptionHandler(IndexingAlreadyRunningException.class)
  public ResponseEntity<IndexingStatusResponse> handleAlreadyRunning(
      IndexingAlreadyRunningException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new IndexingStatusResponse(IndexingStatus.RUNNING, 0, 0, 0, Instant.now())
                .message(ex.getMessage()));
  }

  @GetMapping("/status")
  public IndexingStatusResponse getIndexingStatus() {
    return indexingJobService
        .getLatestJob()
        .map(this::toResponse)
        .orElse(
            new IndexingStatusResponse(IndexingStatus.IDLE, 0, 0, 0, Instant.now())
                .message("Kein Indizierungslauf gefunden"));
  }

  private IndexingStatusResponse toResponse(IndexingJob job) {
    IndexingStatus status = mapStatus(job.getStatus());
    String message =
        switch (job.getStatus()) {
          case RUNNING -> "Indizierung läuft";
          case COMPLETED ->
              "Indizierung abgeschlossen: "
                  + job.getDocumentsProcessed()
                  + " verarbeitet, "
                  + job.getDocumentsSkipped()
                  + " übersprungen, "
                  + job.getDocumentsFailed()
                  + " fehlgeschlagen";
          case FAILED -> "Indizierung fehlgeschlagen: " + job.getErrorMessage();
        };
    return new IndexingStatusResponse(
            status,
            job.getDocumentsProcessed(),
            job.getDocumentsTotal(),
            job.getDocumentsSkipped(),
            job.getCompletedAt() != null ? job.getCompletedAt() : job.getStartedAt())
        .message(message);
  }

  private IndexingStatus mapStatus(JobStatus jobStatus) {
    return switch (jobStatus) {
      case RUNNING -> IndexingStatus.RUNNING;
      case COMPLETED -> IndexingStatus.COMPLETED;
      case FAILED -> IndexingStatus.FAILED;
    };
  }

  private User currentUser(Jwt jwt) {
    String issuer = jwt.getClaimAsString("iss");
    if (issuer == null || issuer.isBlank()) {
      issuer = UNKNOWN_ISSUER;
    }

    return userService
        .findBySubjectAndIssuer(jwt.getSubject(), issuer)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Benutzer nicht gefunden"));
  }
}
