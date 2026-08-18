package io.opaa.indexing;

import io.opaa.api.dto.IndexingTriggerRequest;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import java.net.URI;
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
 *
 * <p>ADR-0017 moved the choice of <em>which</em> run type executes from an implicit guess (was
 * {@code url} set?) to an explicit {@link IndexingSourceType}, resolved through {@link
 * IndexingSourceExecutorRegistry}. {@link #triggerIndexing(IndexingTriggerRequest, UUID, boolean)}
 * is the single place that still falls back to the old guess when the caller does not state a
 * {@code sourceType} - every other method here states its type explicitly and never guesses.
 */
public class DocumentIndexingService {

  private final IndexingJobService indexingJobService;
  private final IndexingSourceExecutorRegistry executorRegistry;
  private final UserRepository userRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryAccessService libraryAccessService;

  public DocumentIndexingService(
      IndexingJobService indexingJobService,
      IndexingSourceExecutorRegistry executorRegistry,
      UserRepository userRepository,
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService libraryAccessService) {
    this.indexingJobService = indexingJobService;
    this.executorRegistry = executorRegistry;
    this.userRepository = userRepository;
    this.libraryRepository = libraryRepository;
    this.libraryAccessService = libraryAccessService;
  }

  /** Triggers a {@link IndexingSourceType#FILESYSTEM} run - the type is never guessed here. */
  public IndexingJob triggerIndexing(UUID libraryId, UUID currentUserId, boolean systemAdmin) {
    return trigger(
        IndexingSourceType.FILESYSTEM,
        new IndexingTriggerRequest().libraryId(libraryId),
        currentUserId,
        systemAdmin);
  }

  /** Triggers a {@link IndexingSourceType#HTTP_DIRECTORY} run - the type is never guessed here. */
  public IndexingJob triggerUrlIndexing(
      UrlIndexingRequest request, UUID libraryId, UUID currentUserId, boolean systemAdmin) {
    if (request.url() == null || request.url().isBlank()) {
      throw new IllegalArgumentException("Die URL darf nicht leer sein");
    }
    return trigger(
        IndexingSourceType.HTTP_DIRECTORY,
        new IndexingTriggerRequest()
            .libraryId(libraryId)
            .url(URI.create(request.url()))
            .proxy(request.proxy())
            .credentials(request.credentials())
            .insecureSsl(request.insecureSsl()),
        currentUserId,
        systemAdmin);
  }

  /**
   * Single entry point used by {@code IndexingController}. Resolves the effective {@link
   * IndexingSourceType} - {@code request.getSourceType()} if the caller stated one, otherwise the
   * backward-compatible fallback derived from whether {@code url} is populated (ADR-0017, decision
   * 1) - checks it against the request's other fields, and delegates to the executor the registry
   * returns for it. This is the only remaining place in the application that infers a source type
   * from field presence rather than being told one explicitly.
   */
  public IndexingJob triggerIndexing(
      IndexingTriggerRequest request, UUID currentUserId, boolean systemAdmin) {
    return trigger(resolveSourceType(request), request, currentUserId, systemAdmin);
  }

  private IndexingSourceType resolveSourceType(IndexingTriggerRequest request) {
    if (request.getSourceType() != null) {
      return request.getSourceType();
    }
    return hasUrl(request) ? IndexingSourceType.HTTP_DIRECTORY : IndexingSourceType.FILESYSTEM;
  }

  private boolean hasUrl(IndexingTriggerRequest request) {
    return request.getUrl() != null && !request.getUrl().toString().isBlank();
  }

  private IndexingJob trigger(
      IndexingSourceType sourceType,
      IndexingTriggerRequest request,
      UUID currentUserId,
      boolean systemAdmin) {
    if (indexingJobService.isJobRunning()) {
      throw new IndexingAlreadyRunningException("An indexing job is already running");
    }
    requireConsistentSourceType(sourceType, request);
    KnowledgeLibrary targetLibrary =
        requireEditableLibrary(request.getLibraryId(), currentUserId, systemAdmin);
    SourceIndexingExecutor executor = executorRegistry.resolve(sourceType);
    var job = indexingJobService.startJob(targetLibrary.getId());
    executor.execute(job.getId(), request, targetLibrary);
    return job;
  }

  /**
   * Rejects a request whose {@code sourceType} contradicts its other fields (ADR-0017): a run that
   * needs an address but got none, or one that must not have one but got one anyway, never starts a
   * job that would find nothing or silently ignore a field the caller set.
   */
  private void requireConsistentSourceType(
      IndexingSourceType sourceType, IndexingTriggerRequest request) {
    boolean hasUrl = hasUrl(request);
    if (sourceType == IndexingSourceType.HTTP_DIRECTORY && !hasUrl) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Der Quellentyp HTTP_DIRECTORY erfordert eine URL");
    }
    if (sourceType == IndexingSourceType.FILESYSTEM && hasUrl) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Der Quellentyp FILESYSTEM darf keine URL enthalten");
    }
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
