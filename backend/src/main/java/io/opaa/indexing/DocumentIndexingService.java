package io.opaa.indexing;

import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates triggering an indexing run for a single knowledge library, reading its own stored
 * quellentyp and quellkonfiguration (ADR-0018, #478). The trigger reduces to "index this library":
 * there is no longer a separate, caller-chosen target and no per-request configuration - a library
 * carries at most one quelle, so the run always writes into the library it reads its configuration
 * from.
 *
 * <p><b>Superseded by ADR-0018.</b> Before this issue, {@code IndexingTriggerRequest} carried
 * {@code libraryId} plus every type-specific field (ADR-0017, Entscheidung 4) and the endpoint
 * required {@code SYSTEM_ADMIN} in addition to an {@code EDITOR} grant on the target library.
 * ADR-0018, Entscheidung 2 drops both: the endpoint now only needs {@code EDITOR} on the library
 * being indexed (see {@link #requireEditableLibrary}), and every field the old request carried now
 * lives on {@link KnowledgeLibrary} itself.
 *
 * <p><b>Concurrency is per library, not global (#478).</b> {@link
 * IndexingJobService#isJobRunning(UUID)} only ever asks about the one library this call targets, so
 * runs of different libraries execute in parallel instead of queuing behind a single global lock.
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

  /**
   * Triggers a run for {@code libraryId}. Resolves and authorizes the library first (404/403, see
   * {@link #requireEditableLibrary}), then rejects a library whose {@code sourceType} has no
   * executor ({@code UPLOAD} - 409, see {@link #toIndexingSourceType}), then rejects a second
   * trigger while a run for this same library is still in progress (409). Only once all three pass
   * does a job actually start.
   *
   * <p>The {@link IndexingJobService#isJobRunning(UUID)} check above is an optimization, not the
   * only guard - two concurrent triggers can both pass it before either has inserted its row.
   * {@link IndexingJobService#startJob(UUID)} closes that TOCTOU gap at the database level (#500
   * review, finding 3, see that method's Javadoc), so the second of two racing triggers still gets
   * 409, just from the database constraint instead of this in-memory check.
   */
  public IndexingJob triggerIndexing(UUID libraryId, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary targetLibrary = requireEditableLibrary(libraryId, currentUserId, systemAdmin);
    IndexingSourceType sourceType = toIndexingSourceType(targetLibrary.getSourceType());
    if (indexingJobService.isJobRunning(targetLibrary.getId())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Fuer diese Bibliothek laeuft bereits ein Indizierungslauf");
    }
    SourceIndexingExecutor executor = executorRegistry.resolve(sourceType);
    var job = indexingJobService.startJob(targetLibrary.getId());
    executor.execute(job.getId(), targetLibrary);
    return job;
  }

  /**
   * The current or most recently completed run for {@code libraryId}, for whoever can at least read
   * the library (a narrower bar than {@link #requireEditableLibrary}'s {@code EDITOR} - seeing the
   * last run's outcome is not the same right as starting a new one).
   */
  public Optional<IndexingJob> getStatus(UUID libraryId, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary library = loadLibraryInOrganization(libraryId, currentUserId);
    if (!libraryAccessService.canRead(library, currentUserId, systemAdmin)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek");
    }
    return indexingJobService.getLatestJob(libraryId);
  }

  /**
   * Maps a library's {@link DocumentSourceType} onto the narrower {@link IndexingSourceType} the
   * registry is keyed on (ADR-0017, decision 1/ADR-0018): every lauf-basierte type maps 1:1, {@code
   * UPLOAD} has no run at all and is rejected with a German 409 - not a 400, since the library
   * itself is a perfectly valid target, it simply has nothing to run (ADR-0018's own acceptance
   * criterion, "UPLOAD-Bibliothek -> 409").
   */
  private IndexingSourceType toIndexingSourceType(DocumentSourceType sourceType) {
    return switch (sourceType) {
      case FILESYSTEM -> IndexingSourceType.FILESYSTEM;
      case HTTP_DIRECTORY -> IndexingSourceType.HTTP_DIRECTORY;
      case RSS_FEED -> IndexingSourceType.RSS_FEED;
      case UPLOAD ->
          throw new ResponseStatusException(
              HttpStatus.CONFLICT, "Fuer UPLOAD-Bibliotheken gibt es keinen Indizierungslauf");
    };
  }

  /**
   * Resolves and authorizes the indexing run's target library: it must resolve to a library in the
   * caller's own organization (otherwise 404, indistinguishable from a library that does not exist
   * at all - the organization boundary must not leak even that much), and the caller must hold at
   * least {@link io.opaa.library.AssetRole#EDITOR} on it (otherwise 403). ADR-0018, Entscheidung 2:
   * unlike the endpoint this replaces, there is no additional {@code SYSTEM_ADMIN} requirement - an
   * "Anstoss-Knopf" only the systemwide administration could ever press would be dead for every
   * other library owner.
   *
   * <p><b>Deliberately no blanket system-admin bypass here</b>, mirroring the endpoint this
   * replaces: {@code canEdit} is always called with {@code systemAdmin = false}, so the real
   * grant/visibility formula decides - the one exception is {@link
   * KnowledgeLibrary#isSystemLibrary() the system library} itself, seeded with no owner and no
   * grants (migration 012), which only a system admin may target without an explicit grant.
   */
  private KnowledgeLibrary requireEditableLibrary(
      UUID libraryId, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary library = loadLibraryInOrganization(libraryId, currentUserId);
    boolean systemAdminOnSystemLibrary = systemAdmin && library.isSystemLibrary();
    if (!systemAdminOnSystemLibrary
        && !libraryAccessService.canEdit(library, currentUserId, false)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek");
    }
    return library;
  }

  /**
   * Loads a library and enforces the organization boundary, treating a library from another
   * organization as not found - mirrors {@code KnowledgeLibraryService#loadLibrary}.
   */
  private KnowledgeLibrary loadLibraryInOrganization(UUID libraryId, UUID currentUserId) {
    User currentUser =
        userRepository
            .findById(currentUserId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Benutzer nicht gefunden"));
    return libraryRepository
        .findById(libraryId)
        .filter(l -> l.getOrganizationId().equals(currentUser.getOrganizationId()))
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bibliothek nicht gefunden"));
  }
}
