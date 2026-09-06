package io.opaa.indexing;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ServiceUnavailableException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.IndexingSourceExecutorRegistry;
import io.opaa.indexing.source.IndexingSourceType;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
   * Triggers a run for {@code libraryId}, in this order: resolve and authorize the library
   * (404/403), reject a {@code sourceType} without an executor (409), reject a second trigger while
   * a run is still in progress (409). That last check is an optimization; {@code startJob} closes
   * the TOCTOU gap in the database. A full executor queue is caught here and fails the job at once,
   * so the just-inserted row cannot stay {@code RUNNING} and the caller gets 503, not a 202.
   */
  public IndexingJob triggerIndexing(UUID libraryId, CurrentUser caller) {
    return triggerIndexing(libraryId, caller, null);
  }

  /**
   * Starts a manual run in {@code requestedRunMode}, or - when {@code null} - in the executor's own
   * default (ADR-0023, Entscheidung 4): the executor's own default for this library (the single
   * mode of a one-mode executor, the state-driven choice of the Confluence executor). A requested
   * mode the executor does not declare is a validation error naming the modes it does.
   */
  public IndexingJob triggerIndexing(
      UUID libraryId, CurrentUser caller, IndexingRunMode requestedRunMode) {
    KnowledgeLibrary targetLibrary = requireEditableLibrary(libraryId, caller);
    IndexingSourceType sourceType = toIndexingSourceType(targetLibrary.getSourceType());
    SourceIndexingExecutor executor = executorRegistry.resolve(sourceType);
    IndexingRunMode runMode = resolveRunMode(executor, targetLibrary, requestedRunMode);
    if (indexingJobService.isJobRunning(targetLibrary.getId(), targetLibrary.getOrganizationId())) {
      throw new ConflictException("Für diese Bibliothek läuft bereits ein Indizierungslauf");
    }
    var job =
        indexingJobService.startJob(
            targetLibrary.getId(),
            targetLibrary.getOrganizationId(),
            JobTriggerSource.MANUAL,
            runMode);
    try {
      executor.execute(job.getId(), targetLibrary, runMode);
    } catch (TaskRejectedException e) {
      indexingJobService.failJob(
          job.getId(), "Indizierungslauf abgelehnt: Kapazität derzeit erschöpft");
      throw new ServiceUnavailableException(
          "Indizierung derzeit nicht möglich, bitte später erneut versuchen", e);
    }
    return job;
  }

  /**
   * Triggers a scheduled run for {@code library}, called only by {@link LibraryIndexingScheduler}:
   * there is no caller to authorize, since the library was selected by its own stored schedule.
   * Otherwise the same shape as {@link #triggerIndexing}, except that a conflict simply propagates
   * as the 409 {@code startJob} already throws for the TOCTOU case.
   */
  public IndexingJob triggerScheduledIndexing(KnowledgeLibrary library) {
    IndexingSourceType sourceType = toIndexingSourceType(library.getSourceType());
    SourceIndexingExecutor executor = executorRegistry.resolve(sourceType);
    IndexingRunMode runMode = resolveRunMode(executor, library, null);
    var job =
        indexingJobService.startJob(
            library.getId(), library.getOrganizationId(), JobTriggerSource.SCHEDULED, runMode);
    try {
      executor.execute(job.getId(), library, runMode);
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
   * the library. Uses {@link LibraryAccessService#requireRole} rather than a plain {@code
   * canRead}/403 check, so a caller with no grant gets a 404 rather than a 403 that gives away the
   * library's existence. {@link IndexingStatusView#canSeeErrorDetail()} reports whether the caller
   * may see a {@code FAILED} run's raw message ({@link AssetRole#MANAGER}).
   */
  public IndexingStatusView getStatus(UUID libraryId, CurrentUser caller) {
    UUID currentUserId = caller.id();
    boolean systemAdmin = caller.isSystemAdmin();
    KnowledgeLibrary library = loadLibraryInOrganization(libraryId, caller);
    libraryAccessService.requireRole(library, currentUserId, systemAdmin, AssetRole.VIEWER);
    boolean canSeeErrorDetail = libraryAccessService.canManage(library, currentUserId, systemAdmin);
    List<String> unreadableSpaceKeys =
        indexingJobService
            .getLatestListingAssessment(libraryId, library.getOrganizationId())
            .map(IndexingJob::getUnreadableSpaceKeys)
            .orElse(List.of());
    return new IndexingStatusView(
        indexingJobService.getLatestJob(libraryId, library.getOrganizationId()),
        canSeeErrorDetail,
        unreadableSpaceKeys);
  }

  /**
   * The last {@value IndexingJobService#MAX_RETAINED_RUNS_PER_LIBRARY} runs for {@code libraryId},
   * newest first, each with its protocol - unlike {@link #getStatus} this requires {@link
   * AssetRole#MANAGER}. An {@link IndexingRunEvent#getReference()} routinely carries the library's
   * own {@code sourcePath}/{@code sourceUrl}, so gating this at {@code canRead} would show a {@code
   * VIEWER} the server's internal filesystem layout or upstream URLs.
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
   * The executor's declaration decides (ADR-0023, Entscheidung 4): a requested mode must be one it
   * supports; without a request the executor's own default for this library applies - the only mode
   * a one-mode executor knows, or for Confluence the mode its sync state calls for (a full run when
   * none completed yet, after a selection change or once the full-sync interval passed, incremental
   * otherwise).
   */
  private static IndexingRunMode resolveRunMode(
      SourceIndexingExecutor executor, KnowledgeLibrary library, IndexingRunMode requested) {
    Set<IndexingRunMode> supported = executor.runModes().keySet();
    if (requested != null) {
      if (!supported.contains(requested)) {
        throw new ValidationException(
            "Betriebsart "
                + requested
                + " ist für Bibliotheken vom Typ "
                + library.getSourceType()
                + " nicht verfügbar; möglich: "
                + supported.stream().sorted().map(Enum::name).collect(Collectors.joining(", ")));
      }
      return requested;
    }
    return executor.defaultRunMode(library);
  }

  /**
   * Maps a library's {@link DocumentSourceType} onto the narrower {@link IndexingSourceType} the
   * registry is keyed on (ADR-0017/ADR-0018): every lauf-basierte type maps 1:1, {@code UPLOAD} has
   * no run at all and is rejected with a German 409 - not a 400, since the library itself is a
   * perfectly valid target, it simply has nothing to run.
   */
  private IndexingSourceType toIndexingSourceType(DocumentSourceType sourceType) {
    if (!sourceType.hasIndexingRun()) {
      throw new ConflictException(
          "Für " + sourceType + "-Bibliotheken gibt es keinen Indizierungslauf");
    }
    return IndexingSourceType.of(sourceType);
  }

  /**
   * Resolves and authorizes the run's target library: it must resolve within the caller's own
   * organization (otherwise 404, indistinguishable from a library that does not exist - the
   * organization boundary must not leak even that), and the caller must hold at least {@link
   * AssetRole#EDITOR} (otherwise 403). {@link LibraryAccessService#requireRole} is always called
   * with {@code systemAdmin = false}, so the real grant formula decides, unconditionally.
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
