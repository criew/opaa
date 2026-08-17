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
   * least {@link io.opaa.library.AssetRole#EDITOR} on it (otherwise 403).
   *
   * <p><b>Deliberately no blanket system-admin bypass here</b> - unlike most other library
   * operations. {@code POST /api/v1/indexing/trigger} already requires {@code SYSTEM_ADMIN} via
   * {@code @PreAuthorize}, so every caller who reaches this method already has that role: bypassing
   * {@link LibraryAccessService#canEdit} for it as well (i.e. calling it with {@code systemAdmin =
   * true}, which resolves to {@code AssetRole#OWNER} unconditionally) would make the check
   * unreachable in practice, not merely lenient - the 403 branch could never fire, and a system
   * admin without any grant could write a whole directory's worth of documents into a library they
   * do not own, including another person's private "Meine Dokumente" (PR #431 review, Befund 2).
   * {@code canEdit} is therefore always called with {@code systemAdmin = false} here, so the real
   * grant/visibility formula decides - the one exception is {@link
   * KnowledgeLibrary#isSystemLibrary() the system library} itself: it is seeded with no owner and
   * no grants (migration 012), so under the ordinary formula literally nobody - not even a system
   * admin - could ever target it, which would silently strand the one path that still writes there
   * today (see {@code FileProcessingService}'s Javadoc). A system admin may therefore target the
   * system library without an explicit grant; every other library needs a real {@code EDITOR} grant
   * or organization-wide visibility, admin status notwithstanding.
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
    boolean systemAdminOnSystemLibrary = systemAdmin && library.isSystemLibrary();
    if (!systemAdminOnSystemLibrary
        && !libraryAccessService.canEdit(library, currentUserId, false)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek");
    }
    return library;
  }
}
