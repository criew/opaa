package io.opaa.api;

import io.opaa.api.dto.AssetGrantRequest;
import io.opaa.api.dto.AssetGrantResponse;
import io.opaa.api.dto.IndexingRunEvent;
import io.opaa.api.dto.IndexingRunEventCategory;
import io.opaa.api.dto.IndexingRunListResponse;
import io.opaa.api.dto.IndexingRunResponse;
import io.opaa.api.dto.IndexingStatus;
import io.opaa.api.dto.IndexingStatusResponse;
import io.opaa.api.dto.IndexingTriggerSource;
import io.opaa.api.dto.LibraryDocumentPageResponse;
import io.opaa.api.dto.LibraryDocumentResponse;
import io.opaa.api.dto.LibraryFolderRenameRequest;
import io.opaa.api.dto.LibraryFolderRequest;
import io.opaa.api.dto.LibraryFolderResponse;
import io.opaa.api.dto.LibraryListResponse;
import io.opaa.api.dto.LibraryRequest;
import io.opaa.api.dto.LibraryResponse;
import io.opaa.api.dto.LibrarySpaceAssociationResponse;
import io.opaa.api.dto.LibraryUpdateRequest;
import io.opaa.api.dto.SourceConnectionTestRequest;
import io.opaa.api.dto.SourceConnectionTestResponse;
import io.opaa.auth.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.indexing.DocumentIndexingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingRunDetail;
import io.opaa.indexing.IndexingStatusView;
import io.opaa.indexing.JobStatus;
import io.opaa.library.AssetGrantService;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryDocumentService;
import io.opaa.library.LibraryFolderService;
import io.opaa.library.SourceConnectionTestService;
import io.opaa.space.SpaceAssetAssociationService;
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
import org.springframework.web.bind.annotation.PatchMapping;
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
  private final LibraryFolderService folderService;
  private final DocumentIndexingService indexingService;
  private final UserService userService;
  private final SourceConnectionTestService sourceConnectionTestService;
  private final SpaceAssetAssociationService associationService;

  public LibraryController(
      KnowledgeLibraryService libraryService,
      AssetGrantService grantService,
      LibraryDocumentService documentService,
      LibraryFolderService folderService,
      DocumentIndexingService indexingService,
      UserService userService,
      SourceConnectionTestService sourceConnectionTestService,
      SpaceAssetAssociationService associationService) {
    this.libraryService = libraryService;
    this.grantService = grantService;
    this.documentService = documentService;
    this.folderService = folderService;
    this.indexingService = indexingService;
    this.userService = userService;
    this.sourceConnectionTestService = sourceConnectionTestService;
    this.associationService = associationService;
  }

  @GetMapping("/{libraryId}/spaces")
  public List<LibrarySpaceAssociationResponse> listSpaceAssociations(
      @PathVariable UUID libraryId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return SpaceLibraryAssociationResponseMapper.toLibrarySpaceResponses(
        associationService.listForLibrary(
            libraryId,
            currentUser.getId(),
            currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN));
  }

  @PostMapping
  public ResponseEntity<LibraryResponse> createLibrary(
      @Valid @RequestBody LibraryRequest request, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    LibraryResponse response =
        LibraryResponseMapper.toResponse(
            libraryService.createLibrary(
                LibraryResponseMapper.toCreation(request), currentUser.getId()));
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  // #514: without libraryId, same permission bar as createLibrary above - any authenticated,
  // known user. There is no library yet at this point for a role to be checked against, and
  // creating one carries no higher bar than being an organization member (see
  // KnowledgeLibraryService#createLibrary). With libraryId set (#544), SourceConnectionTestService
  // itself enforces the additional MANAGER bar on that library, passed the same systemAdmin flag
  // updateLibrary below gets (#615 review, finding 3) - a SYSTEM_ADMIN who can save the
  // quellkonfiguration without a grant must not have "Verbindung testen" fail 404 right before it.
  @PostMapping("/source-test")
  public SourceConnectionTestResponse testLibrarySource(
      @Valid @RequestBody SourceConnectionTestRequest request, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return sourceConnectionTestService.test(
        request, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
  }

  @GetMapping
  public List<LibraryListResponse> listLibraries(@AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return LibraryResponseMapper.toListResponses(
        libraryService.listLibraries(
            currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN));
  }

  @GetMapping("/{libraryId}")
  public LibraryResponse getLibrary(
      @PathVariable UUID libraryId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return LibraryResponseMapper.toResponse(
        libraryService.getLibrary(
            libraryId,
            currentUser.getId(),
            currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN));
  }

  @PutMapping("/{libraryId}")
  public LibraryResponse updateLibrary(
      @PathVariable UUID libraryId,
      @Valid @RequestBody LibraryUpdateRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return LibraryResponseMapper.toResponse(
        libraryService.updateLibrary(
            libraryId,
            LibraryResponseMapper.toUpdate(request),
            currentUser.getId(),
            currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN));
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
      @RequestParam(required = false) UUID folderId,
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
    return LibraryDocumentResponseMapper.toPageResponse(
        libraryService.listDocuments(
            libraryId,
            currentUser.getId(),
            currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN,
            q,
            folderId,
            pageable));
  }

  @PostMapping(value = "/{libraryId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<LibraryDocumentResponse> uploadDocument(
      @PathVariable UUID libraryId,
      @RequestParam("file") MultipartFile file,
      @RequestParam(required = false) UUID folderId,
      @RequestParam(required = false) String folderPath,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    LibraryDocumentResponse response =
        LibraryDocumentResponseMapper.toResponse(
            documentService.uploadDocument(
                libraryId,
                file,
                folderId,
                folderPath,
                currentUser.getId(),
                currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN));
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

  @PostMapping("/{libraryId}/folders")
  public ResponseEntity<LibraryFolderResponse> createFolder(
      @PathVariable UUID libraryId,
      @Valid @RequestBody LibraryFolderRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    LibraryFolderResponse response =
        folderService.createFolder(
            libraryId,
            request,
            currentUser.getId(),
            currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping("/{libraryId}/folders/{folderId}")
  public LibraryFolderResponse getFolder(
      @PathVariable UUID libraryId, @PathVariable UUID folderId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return folderService.getFolder(
        libraryId,
        folderId,
        currentUser.getId(),
        currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
  }

  @PatchMapping("/{libraryId}/folders/{folderId}")
  public LibraryFolderResponse renameFolder(
      @PathVariable UUID libraryId,
      @PathVariable UUID folderId,
      @Valid @RequestBody LibraryFolderRenameRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return folderService.renameFolder(
        libraryId,
        folderId,
        request,
        currentUser.getId(),
        currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
  }

  @DeleteMapping("/{libraryId}/folders/{folderId}")
  public ResponseEntity<Void> deleteFolder(
      @PathVariable UUID libraryId, @PathVariable UUID folderId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    folderService.deleteFolder(
        libraryId,
        folderId,
        currentUser.getId(),
        currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{libraryId}/grants")
  public List<AssetGrantResponse> listAssetGrants(
      @PathVariable UUID libraryId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return AssetGrantResponseMapper.toResponses(
        grantService.listGrants(
            libraryId,
            currentUser.getId(),
            currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN));
  }

  @PostMapping("/{libraryId}/grants")
  public AssetGrantResponse upsertAssetGrant(
      @PathVariable UUID libraryId,
      @Valid @RequestBody AssetGrantRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return AssetGrantResponseMapper.toResponse(
        grantService.upsertGrant(
            libraryId,
            AssetGrantResponseMapper.toUpsert(request),
            currentUser.getId(),
            currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN));
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
    IndexingStatusView view =
        indexingService.getStatus(
            libraryId, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
    return view.job()
        .map(job -> toIndexingStatusResponse(job, view.canSeeErrorDetail()))
        .orElse(
            new IndexingStatusResponse(IndexingStatus.IDLE, 0, 0, 0, 0, 0, Instant.now())
                .message("Kein Indizierungslauf gefunden")
                .libraryId(libraryId));
  }

  @GetMapping("/{libraryId}/indexing/runs")
  public IndexingRunListResponse listIndexingRuns(
      @PathVariable UUID libraryId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    var runs =
        indexingService.getRecentRuns(
            libraryId, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
    return new IndexingRunListResponse(runs.stream().map(this::toIndexingRunResponse).toList());
  }

  private IndexingRunResponse toIndexingRunResponse(IndexingRunDetail detail) {
    IndexingJob job = detail.job();
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
    return new IndexingRunResponse(
            job.getId(),
            status,
            mapIndexingTriggerSource(job.getTriggeredBy()),
            job.getDocumentsProcessed(),
            job.getDocumentsTotal(),
            job.getDocumentsSkipped(),
            job.getDocumentsFailed(),
            job.getDocumentsIndexedTotal(),
            job.getStartedAt(),
            detail.events().stream().map(this::toIndexingRunEventResponse).toList(),
            job.getEventsTruncatedCount())
        .message(message)
        .completedAt(job.getCompletedAt());
  }

  private IndexingTriggerSource mapIndexingTriggerSource(
      io.opaa.indexing.JobTriggerSource triggeredBy) {
    return switch (triggeredBy) {
      case MANUAL -> IndexingTriggerSource.MANUAL;
      case SCHEDULED -> IndexingTriggerSource.SCHEDULED;
    };
  }

  private IndexingRunEvent toIndexingRunEventResponse(io.opaa.indexing.IndexingRunEvent event) {
    return new IndexingRunEvent(mapIndexingEventCategory(event.getCategory()), event.getMessage())
        .reference(event.getReference());
  }

  private IndexingRunEventCategory mapIndexingEventCategory(IndexingEventCategory category) {
    return switch (category) {
      case REJECTED -> IndexingRunEventCategory.REJECTED;
      case UNREACHABLE -> IndexingRunEventCategory.UNREACHABLE;
      case SCHEDULE_SKIPPED -> IndexingRunEventCategory.SCHEDULE_SKIPPED;
      case UNSUPPORTED_FORMAT -> IndexingRunEventCategory.UNSUPPORTED_FORMAT;
      case ALLOWLIST -> IndexingRunEventCategory.ALLOWLIST;
      case ERROR -> IndexingRunEventCategory.ERROR;
      case FORMAT_MISMATCH -> IndexingRunEventCategory.FORMAT_MISMATCH;
    };
  }

  // triggerIndexing (above) always hands back a freshly started job (JobStatus.RUNNING) - the
  // FAILED branch below is unreachable from that call site, so whether it may see the error
  // detail is moot there. true keeps that call site's intent transparent instead of threading a
  // meaningless boolean through it.
  private IndexingStatusResponse toIndexingStatusResponse(IndexingJob job) {
    return toIndexingStatusResponse(job, true);
  }

  // #507/#659: a FAILED job's errorMessage is the raw exception message from the executor that
  // ran it - a NoSuchFileException's absolute server path, a ConnectException's/
  // UnknownHostException's host:port - exactly the internal-infrastructure leak #507 already
  // closes for the source configuration display. canSeeErrorDetail (MANAGER+, from
  // DocumentIndexingService#getStatus) gates it the same way, without shortening it for RUNNING/
  // COMPLETED, which never carry that detail in the first place.
  private IndexingStatusResponse toIndexingStatusResponse(
      IndexingJob job, boolean canSeeErrorDetail) {
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
          case FAILED ->
              canSeeErrorDetail
                  ? "Indizierung fehlgeschlagen: " + job.getErrorMessage()
                  : "Indizierung fehlgeschlagen. Details sind für Verwaltende sichtbar.";
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
