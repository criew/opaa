package io.opaa.api;

import io.opaa.api.dto.AssetGrantRequest;
import io.opaa.api.dto.AssetGrantResponse;
import io.opaa.api.dto.IndexingStatus;
import io.opaa.api.dto.IndexingStatusResponse;
import io.opaa.api.dto.LibraryDocumentPageResponse;
import io.opaa.api.dto.LibraryDocumentResponse;
import io.opaa.api.dto.LibraryListResponse;
import io.opaa.api.dto.LibraryRequest;
import io.opaa.api.dto.LibraryResponse;
import io.opaa.api.dto.LibraryUpdateRequest;
import io.opaa.auth.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.indexing.DocumentIndexingService;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.JobStatus;
import io.opaa.library.AssetGrantService;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryDocumentService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/libraries")
public class LibraryController {

  private static final String UNKNOWN_ISSUER = "unknown";

  private final KnowledgeLibraryService libraryService;
  private final AssetGrantService grantService;
  private final LibraryDocumentService documentService;
  private final DocumentIndexingService indexingService;
  private final UserService userService;

  public LibraryController(
      KnowledgeLibraryService libraryService,
      AssetGrantService grantService,
      LibraryDocumentService documentService,
      DocumentIndexingService indexingService,
      UserService userService) {
    this.libraryService = libraryService;
    this.grantService = grantService;
    this.documentService = documentService;
    this.indexingService = indexingService;
    this.userService = userService;
  }

  @PostMapping
  public ResponseEntity<LibraryResponse> createLibrary(
      @Valid @RequestBody LibraryRequest request, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    LibraryResponse response = libraryService.createLibrary(request, currentUser.getId());
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping
  public List<LibraryListResponse> listLibraries(@AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return libraryService.listLibraries(
        currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
  }

  @GetMapping("/{libraryId}")
  public LibraryResponse getLibrary(
      @PathVariable UUID libraryId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return libraryService.getLibrary(
        libraryId, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
  }

  @PutMapping("/{libraryId}")
  public LibraryResponse updateLibrary(
      @PathVariable UUID libraryId,
      @Valid @RequestBody LibraryUpdateRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return libraryService.updateLibrary(
        libraryId,
        request,
        currentUser.getId(),
        currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
  }

  @DeleteMapping("/{libraryId}")
  public ResponseEntity<Void> deleteLibrary(
      @PathVariable UUID libraryId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    libraryService.deleteLibrary(
        libraryId, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{libraryId}/documents")
  public LibraryDocumentPageResponse listDocuments(
      @PathVariable UUID libraryId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String q,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    // #517 code review, finding 2: the spec promises 1..100 - silently clamping an out-of-range
    // value would contradict that promise (size=500 quietly answering 100, size=0 quietly
    // answering 1), so both bounds and page<0 are rejected the same way bean validation would.
    if (size < 1 || size > 100) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "size muss zwischen 1 und 100 liegen");
    }
    if (page < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page darf nicht negativ sein");
    }
    // #517 code review, finding 1: LIMIT/OFFSET without ORDER BY has no stable row order across
    // separate statements in PostgreSQL - two pages fetched moments apart (or the same page
    // re-fetched by documentStore#startPolling's 3s poll while indexing concurrently updates a
    // row) could otherwise return a document twice or skip it entirely. fileName first (the
    // column users actually browse/search by), id as a tiebreaker for documents sharing a name.
    Pageable pageable =
        PageRequest.of(page, size, Sort.by(Sort.Order.asc("fileName"), Sort.Order.asc("id")));
    return libraryService.listDocuments(
        libraryId,
        currentUser.getId(),
        currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN,
        q,
        pageable);
  }

  @PostMapping(value = "/{libraryId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<LibraryDocumentResponse> uploadDocument(
      @PathVariable UUID libraryId,
      @RequestParam("file") MultipartFile file,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    LibraryDocumentResponse response =
        documentService.uploadDocument(
            libraryId,
            file,
            currentUser.getId(),
            currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @DeleteMapping("/{libraryId}/documents/{documentId}")
  public ResponseEntity<Void> deleteDocument(
      @PathVariable UUID libraryId,
      @PathVariable UUID documentId,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    documentService.deleteDocument(
        libraryId,
        documentId,
        currentUser.getId(),
        currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{libraryId}/grants")
  public List<AssetGrantResponse> listAssetGrants(
      @PathVariable UUID libraryId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return grantService.listGrants(
        libraryId, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
  }

  @PostMapping("/{libraryId}/grants")
  public AssetGrantResponse upsertAssetGrant(
      @PathVariable UUID libraryId,
      @Valid @RequestBody AssetGrantRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return grantService.upsertGrant(
        libraryId,
        request,
        currentUser.getId(),
        currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
  }

  @DeleteMapping("/{libraryId}/grants/{grantId}")
  public ResponseEntity<Void> revokeAssetGrant(
      @PathVariable UUID libraryId, @PathVariable UUID grantId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    grantService.revokeGrant(
        libraryId,
        grantId,
        currentUser.getId(),
        currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{libraryId}/indexing")
  public ResponseEntity<IndexingStatusResponse> triggerIndexing(
      @PathVariable UUID libraryId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    IndexingJob job =
        indexingService.triggerIndexing(
            libraryId, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(toIndexingStatusResponse(job));
  }

  @GetMapping("/{libraryId}/indexing/status")
  public IndexingStatusResponse getIndexingStatus(
      @PathVariable UUID libraryId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return indexingService
        .getStatus(
            libraryId, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN)
        .map(this::toIndexingStatusResponse)
        .orElse(
            new IndexingStatusResponse(IndexingStatus.IDLE, 0, 0, 0, 0, 0, Instant.now())
                .message("Kein Indizierungslauf gefunden")
                .libraryId(libraryId));
  }

  private IndexingStatusResponse toIndexingStatusResponse(IndexingJob job) {
    IndexingStatus status = mapIndexingStatus(job.getStatus());
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
            job.getDocumentsFailed(),
            job.getDocumentsIndexedTotal(),
            job.getCompletedAt() != null ? job.getCompletedAt() : job.getStartedAt())
        .message(message)
        .libraryId(job.getLibraryId());
  }

  private IndexingStatus mapIndexingStatus(JobStatus jobStatus) {
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
