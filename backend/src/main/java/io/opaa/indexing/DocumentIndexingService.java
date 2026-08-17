package io.opaa.indexing;

import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates triggering an indexing run - directory or URL - into a caller-chosen target library
 * (#419). {@code libraryId} is deliberately mandatory on every trigger: a directory-wide or
 * URL-wide run that silently defaulted to some library (the caller's personal one, or worse, the
 * unreachable {@link KnowledgeLibrary#SYSTEM_LIBRARY_ID}) would file a whole batch of documents
 * somewhere nobody chose, exactly the defect this issue closes (see the class-level rationale that
 * used to live on {@code FileProcessingService} before #419).
 */
public class DocumentIndexingService {

  private final IndexingJobService indexingJobService;
  private final AsyncIndexingExecutor asyncIndexingExecutor;
  private final UrlIndexingExecutor urlIndexingExecutor;
  private final UserRepository userRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryAccessService libraryAccessService;

  public DocumentIndexingService(
      IndexingJobService indexingJobService,
      AsyncIndexingExecutor asyncIndexingExecutor,
      UrlIndexingExecutor urlIndexingExecutor,
      UserRepository userRepository,
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService libraryAccessService) {
    this.indexingJobService = indexingJobService;
    this.asyncIndexingExecutor = asyncIndexingExecutor;
    this.urlIndexingExecutor = urlIndexingExecutor;
    this.userRepository = userRepository;
    this.libraryRepository = libraryRepository;
    this.libraryAccessService = libraryAccessService;
  }

  public IndexingJob triggerIndexing(UUID libraryId, UUID currentUserId, boolean systemAdmin) {
    if (indexingJobService.isJobRunning()) {
      throw new IndexingAlreadyRunningException("An indexing job is already running");
    }
    KnowledgeLibrary targetLibrary = requireEditableLibrary(libraryId, currentUserId, systemAdmin);
    var job = indexingJobService.startJob(targetLibrary.getId());
    asyncIndexingExecutor.execute(job.getId(), targetLibrary);
    return job;
  }

  public IndexingJob triggerUrlIndexing(
      UrlIndexingRequest request, UUID libraryId, UUID currentUserId, boolean systemAdmin) {
    if (indexingJobService.isJobRunning()) {
      throw new IndexingAlreadyRunningException("An indexing job is already running");
    }
    if (request.url() == null || request.url().isBlank()) {
      throw new IllegalArgumentException("Die URL darf nicht leer sein");
    }
    KnowledgeLibrary targetLibrary = requireEditableLibrary(libraryId, currentUserId, systemAdmin);
    var job = indexingJobService.startJob(targetLibrary.getId());
    urlIndexingExecutor.execute(job.getId(), request, targetLibrary);
    return job;
  }

  /**
   * Resolves and authorizes the indexing run's target library: {@code libraryId} must be present
   * (400, German message - #419 deliberately has no default), must resolve to a library in the
   * caller's own organization (otherwise 404, indistinguishable from a library that does not exist
   * at all - the organization boundary must not leak even that much), and the caller must hold at
   * least {@link io.opaa.library.AssetRole#EDITOR} on it (otherwise 403). System admins still
   * bypass the role check, exactly as {@link LibraryAccessService#effectiveRole} already does for
   * every other library operation - the {@code SYSTEM_ADMIN} requirement on {@code POST
   * /api/v1/indexing/trigger} stays in place alongside this check, not instead of it.
   */
  private KnowledgeLibrary requireEditableLibrary(
      UUID libraryId, UUID currentUserId, boolean systemAdmin) {
    if (libraryId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "libraryId ist erforderlich");
    }
    User currentUser =
        userRepository
            .findById(currentUserId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Benutzer nicht gefunden"));
    KnowledgeLibrary library =
        libraryRepository
            .findById(libraryId)
            .filter(l -> l.getOrganizationId().equals(currentUser.getOrganizationId()))
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Bibliothek nicht gefunden"));
    if (!libraryAccessService.canEdit(library, currentUserId, systemAdmin)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek");
    }
    return library;
  }
}
