package io.opaa.indexing;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ServiceUnavailableException;
import io.opaa.indexing.source.IndexingSourceExecutorRegistry;
import io.opaa.indexing.source.IndexingSourceType;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import java.util.List;
import java.util.UUID;
import org.springframework.core.task.TaskRejectedException;

/**
 * Orchestrates triggering an indexing run for a single knowledge library, reading its own stored
 * quellentyp and quellkonfiguration (ADR-0018). The trigger reduces to "index this library": there
 * is no longer a separate, caller-chosen target and no per-request configuration - a library
 * carries at most one quelle, so the run always writes into the library it reads its configuration
 * from. The endpoint only needs {@code EDITOR} on the library being indexed (see {@link
 * #requireEditableLibrary}), no additional {@code SYSTEM_ADMIN} requirement.
 *
 * <p>Concurrency is per library, not global: {@link IndexingJobService#isJobRunning(UUID)} only
 * ever asks about the one library this call targets, so runs of different libraries execute in
 * parallel instead of queuing behind a single global lock.
 */
public class DocumentIndexingService {

  private final IndexingJobService indexingJobService;
  private final IndexingSourceExecutorRegistry executorRegistry;
  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryAccessService libraryAccessService;
  private final IndexingRunEventRepository indexingRunEventRepository;

  public DocumentIndexingService(
      IndexingJobService indexingJobService,
      IndexingSourceExecutorRegistry executorRegistry,
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService libraryAccessService,
      IndexingRunEventRepository indexingRunEventRepository) {
    this.indexingJobService = indexingJobService;
    this.executorRegistry = executorRegistry;
    this.libraryRepository = libraryRepository;
    this.libraryAccessService = libraryAccessService;
    this.indexingRunEventRepository = indexingRunEventRepository;
  }

  /**
   * Triggers a run for {@code libraryId}. Resolves and authorizes the library first (404/403, see
   * {@link #requireEditableLibrary}), then rejects a library whose {@code sourceType} has no
   * executor ({@code UPLOAD} - 409, see {@link #toIndexingSourceType}), then rejects a second
   * trigger while a run for this same library is still in progress (409). Only once all three pass
   * does a job actually start.
   *
   * <p>The {@link IndexingJobService#isJobRunning(UUID, UUID)} check above is an optimization, not
   * the only guard - two concurrent triggers can both pass it before either has inserted its row.
   * {@link IndexingJobService#startJob(UUID, UUID)} closes that TOCTOU gap at the database level,
   * so the second of two racing triggers still gets 409, just from the database constraint instead
   * of this in-memory check.
   *
   * <p>A full {@code indexingTaskExecutor} queue must not leave the just-inserted row {@code
   * RUNNING} forever. {@code executor.execute} is an {@code @Async} void method; when the pool's
   * queue is full, {@code AbortPolicy} makes the submission throw {@link TaskRejectedException}
   * synchronously, on this thread, before the run ever starts. Left uncaught, the row {@link
   * IndexingJobService#startJob} just committed would stay {@code RUNNING} with nothing left to
   * ever complete it. Catching it here and failing the job immediately keeps that row's lifecycle
   * intact and answers the caller with 503 instead of a misleading 202.
   */
  public IndexingJob triggerIndexing(UUID libraryId, CurrentUser caller) {
    KnowledgeLibrary targetLibrary = requireEditableLibrary(libraryId, caller);
    IndexingSourceType sourceType = toIndexingSourceType(targetLibrary.getSourceType());
    if (indexingJobService.isJobRunning(targetLibrary.getId(), targetLibrary.getOrganizationId())) {
      throw new ConflictException("Für diese Bibliothek läuft bereits ein Indizierungslauf");
    }
    SourceIndexingExecutor executor = executorRegistry.resolve(sourceType);
    var job = indexingJobService.startJob(targetLibrary.getId(), targetLibrary.getOrganizationId());
    try {
      executor.execute(job.getId(), targetLibrary);
    } catch (TaskRejectedException e) {
      indexingJobService.failJob(
          job.getId(), "Indizierungslauf abgelehnt: Kapazität derzeit erschöpft");
      throw new ServiceUnavailableException(
          "Indizierung derzeit nicht möglich, bitte später erneut versuchen", e);
    }
    return job;
  }

  /**
   * Triggers a scheduled run for {@code library} - called only by {@link LibraryIndexingScheduler},
   * never from an HTTP request, so unlike {@link #triggerIndexing} there is no caller to authorize:
   * the library was already selected because its own stored schedule says it is due. Otherwise
   * mirrors {@link #triggerIndexing}'s shape ({@code isJobRunning} pre-check, {@code
   * TaskRejectedException} handling), except a conflict here simply propagates as the same 409
   * {@link IndexingJobService#startJob(UUID, UUID, JobTriggerSource)} already throws for the TOCTOU
   * case.
   */
  public IndexingJob triggerScheduledIndexing(KnowledgeLibrary library) {
    IndexingSourceType sourceType = toIndexingSourceType(library.getSourceType());
    SourceIndexingExecutor executor = executorRegistry.resolve(sourceType);
    var job =
        indexingJobService.startJob(
            library.getId(), library.getOrganizationId(), JobTriggerSource.SCHEDULED);
    try {
      executor.execute(job.getId(), library);
    } catch (TaskRejectedException e) {
      indexingJobService.failJob(
          job.getId(), "Indizierungslauf abgelehnt: Kapazität derzeit erschöpft");
      throw new ServiceUnavailableException(
          "Indizierung derzeit nicht möglich, bitte später erneut versuchen", e);
    }
    return job;
  }

  /**
   * The current or most recently completed run for {@code libraryId}, for whoever can at least read
   * the library (a narrower bar than {@link #requireEditableLibrary}'s {@code EDITOR}). Uses {@link
   * LibraryAccessService#requireRole} rather than a plain {@code canRead}/403 check, so a caller
   * with no grant at all on the library gets the same 404 {@code GET /libraries/{id}} already
   * answers, instead of a 403 that gives away the library's existence.
   *
   * <p>The returned {@link IndexingStatusView#canSeeErrorDetail()} additionally reports whether the
   * caller may see a {@code FAILED} job's raw error message - requires {@link AssetRole#MANAGER},
   * the same bar {@link #getRecentRuns} already enforces for the run history's own leak-prone
   * detail. The caller (the controller) is responsible for actually shortening the message when
   * this is {@code false}; this method only decides the permission.
   */
  public IndexingStatusView getStatus(UUID libraryId, CurrentUser caller) {
    UUID currentUserId = caller.id();
    boolean systemAdmin = caller.isSystemAdmin();
    KnowledgeLibrary library = loadLibraryInOrganization(libraryId, caller);
    libraryAccessService.requireRole(library, currentUserId, systemAdmin, AssetRole.VIEWER);
    boolean canSeeErrorDetail = libraryAccessService.canManage(library, currentUserId, systemAdmin);
    return new IndexingStatusView(
        indexingJobService.getLatestJob(libraryId, library.getOrganizationId()), canSeeErrorDetail);
  }

  /**
   * The last {@value IndexingJobService#MAX_RETAINED_RUNS_PER_LIBRARY} runs for {@code libraryId},
   * newest first, each with its own protocol - unlike {@link #getStatus}, this requires {@link
   * AssetRole#MANAGER}, not just {@code canRead}.
   *
   * <p>An {@link IndexingRunEvent#getReference()} routinely carries the library's own {@code
   * sourcePath}/{@code sourceUrl} (a rejected file's absolute server path, a skipped entry's source
   * URL) - an internal-path leak. Gating this at {@code canRead} (the same bar as the harmless
   * counters {@link #getStatus} exposes) would reopen that leak: a {@code VIEWER} on an
   * organization-wide connector library would see the server's internal filesystem layout or
   * upstream URLs it was never granted access to. {@code canManage} mirrors {@code
   * KnowledgeLibraryService#updateLibrary}'s own bar for touching the source configuration.
   */
  public List<IndexingRunDetail> getRecentRuns(UUID libraryId, CurrentUser caller) {
    KnowledgeLibrary library = loadLibraryInOrganization(libraryId, caller);
    if (!libraryAccessService.canManage(library, caller.id(), caller.isSystemAdmin())) {
      throw new AccessDeniedException("Kein Zugriff auf diese Bibliothek");
    }
    return indexingJobService.getRecentJobs(libraryId, library.getOrganizationId()).stream()
        .map(
            job ->
                new IndexingRunDetail(
                    job, indexingRunEventRepository.findByJobIdOrderByCreatedAtAsc(job.getId())))
        .toList();
  }

  /**
   * Maps a library's {@link DocumentSourceType} onto the narrower {@link IndexingSourceType} the
   * registry is keyed on (ADR-0017/ADR-0018): every lauf-basierte type maps 1:1, {@code UPLOAD} has
   * no run at all and is rejected with a German 409 - not a 400, since the library itself is a
   * perfectly valid target, it simply has nothing to run.
   */
  private IndexingSourceType toIndexingSourceType(DocumentSourceType sourceType) {
    return switch (sourceType) {
      case FILESYSTEM -> IndexingSourceType.FILESYSTEM;
      case HTTP_DIRECTORY -> IndexingSourceType.HTTP_DIRECTORY;
      case RSS_FEED -> IndexingSourceType.RSS_FEED;
      case UPLOAD ->
          throw new ConflictException("Für UPLOAD-Bibliotheken gibt es keinen Indizierungslauf");
    };
  }

  /**
   * Resolves and authorizes the indexing run's target library: it must resolve to a library in the
   * caller's own organization (otherwise 404, indistinguishable from a library that does not exist
   * at all - the organization boundary must not leak even that much), and the caller must hold at
   * least {@link AssetRole#EDITOR} on it (otherwise 403). No additional {@code SYSTEM_ADMIN}
   * requirement, and no blanket system-admin bypass: {@link LibraryAccessService#requireRole} is
   * always called with {@code systemAdmin = false}, so the real grant/visibility formula decides,
   * unconditionally.
   *
   * <p>Uses {@link LibraryAccessService#requireRole} instead of a plain boolean role check/403 for
   * the same reason {@link #getStatus} does: "no access at all" must answer 404, not a 403 that
   * confirms the library exists.
   */
  private KnowledgeLibrary requireEditableLibrary(UUID libraryId, CurrentUser caller) {
    KnowledgeLibrary library = loadLibraryInOrganization(libraryId, caller);
    libraryAccessService.requireRole(library, caller.id(), false, AssetRole.EDITOR);
    return library;
  }

  /**
   * Loads a library and enforces the organization boundary, treating a library from another
   * organization as not found - mirrors {@code KnowledgeLibraryService#loadLibrary}.
   */
  private KnowledgeLibrary loadLibraryInOrganization(UUID libraryId, CurrentUser caller) {
    return libraryRepository
        .findById(libraryId)
        .filter(l -> l.getOrganizationId().equals(caller.organizationId()))
        .orElseThrow(() -> new NotFoundException("Bibliothek nicht gefunden"));
  }
}
