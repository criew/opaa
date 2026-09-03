package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ServiceUnavailableException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.IndexingSourceExecutorRegistry;
import io.opaa.indexing.source.IndexingSourceType;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

/**
 * #478/ADR-0018: triggering an indexing run reduces to "index this library" - type and
 * configuration are read from the library itself, and concurrency is tracked per library instead of
 * globally. Supersedes the pre-#478 {@code DocumentIndexingServiceTest}, which pinned the old
 * {@code IndexingTriggerRequest}-based entry point and its ADR-0017 fallback/contradiction checks -
 * both gone with this issue (ADR-0018, Entscheidung 2).
 */
@ExtendWith(MockitoExtension.class)
class DocumentIndexingServiceTest {

  @Mock private IndexingJobService indexingJobService;
  @Mock private SourceIndexingExecutor asyncIndexingExecutor;
  @Mock private SourceIndexingExecutor urlIndexingExecutor;
  @Mock private SourceIndexingExecutor rssFeedIndexingExecutor;
  @Mock private SourceIndexingExecutor confluenceIndexingExecutor;
  @Mock private KnowledgeLibraryRepository libraryRepository;
  @Mock private LibraryAccessService libraryAccessService;
  @Mock private IndexingRunEventRepository indexingRunEventRepository;

  private DocumentIndexingService service;

  private final UUID organizationId = UUID.randomUUID();
  private User currentUser;
  private CurrentUser caller;
  private CurrentUser systemAdminCaller;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    when(asyncIndexingExecutor.sourceType()).thenReturn(IndexingSourceType.FILESYSTEM);
    when(urlIndexingExecutor.sourceType()).thenReturn(IndexingSourceType.HTTP_DIRECTORY);
    when(rssFeedIndexingExecutor.sourceType()).thenReturn(IndexingSourceType.RSS_FEED);
    when(confluenceIndexingExecutor.sourceType()).thenReturn(IndexingSourceType.CONFLUENCE);
    var registry =
        new IndexingSourceExecutorRegistry(
            List.of(
                asyncIndexingExecutor,
                urlIndexingExecutor,
                rssFeedIndexingExecutor,
                confluenceIndexingExecutor));
    service =
        new DocumentIndexingService(
            indexingJobService,
            registry,
            libraryRepository,
            libraryAccessService,
            indexingRunEventRepository);

    currentUser = new User("subject", "issuer", "user@example.com", "Test User");
    currentUser.setOrganizationId(organizationId);
    caller =
        CurrentUser.of(
            currentUser.getId(), organizationId, io.opaa.api.types.SystemRole.USER, "Test User");
    systemAdminCaller =
        CurrentUser.of(
            currentUser.getId(),
            organizationId,
            io.opaa.api.types.SystemRole.SYSTEM_ADMIN,
            "Test User");
    library =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Zielbibliothek",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.FILESYSTEM,
            "/data/docs",
            null,
            null,
            null,
            false);
  }

  private void stubEditableLibrary() {
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.requireRole(library, caller.id(), false, AssetRole.EDITOR))
        .thenReturn(AssetRole.EDITOR);
  }

  @Test
  void triggerIndexingWithAViewerOnlyGrantFailsWithForbiddenAndDoesNotStartAJob() {
    // Some access (a real VIEWER grant), just not enough - #436 keeps this at 403, distinct from
    // "no access at all" (see aSystemAdminWithoutAGrantOnAnOrdinaryLibraryIsStillRejected below).
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.requireRole(library, caller.id(), false, AssetRole.EDITOR))
        .thenThrow(new AccessDeniedException("Kein Zugriff auf diese Bibliothek"));

    assertThatThrownBy(() -> service.triggerIndexing(library.getId(), caller))
        .isInstanceOf(AccessDeniedException.class);
    verify(indexingJobService, never()).startJob(any(), any(), any(), any());
    verify(asyncIndexingExecutor, never()).execute(any(), any(), any());
  }

  @Test
  void triggerIndexingWithALibraryFromAnotherOrganizationFailsWithNotFound() {
    // A library belonging to a foreign organization must not be distinguishable from one that
    // does not exist at all.
    KnowledgeLibrary foreignLibrary =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Fremde Bibliothek",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false);
    when(libraryRepository.findById(foreignLibrary.getId()))
        .thenReturn(Optional.of(foreignLibrary));

    assertThatThrownBy(() -> service.triggerIndexing(foreignLibrary.getId(), caller))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Bibliothek nicht gefunden");
    verify(indexingJobService, never()).startJob(any(), any(), any(), any());
  }

  @Test
  void triggerIndexingWithAnUnknownLibraryFailsWithNotFound() {
    UUID unknownLibraryId = UUID.randomUUID();
    when(libraryRepository.findById(unknownLibraryId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.triggerIndexing(unknownLibraryId, caller))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void triggerIndexingWithAnEditorGrantStartsTheJobAgainstTheLibrarysOwnConfiguration() {
    stubEditableLibrary();
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.startJob(
            library.getId(), organizationId, JobTriggerSource.MANUAL, IndexingRunMode.FULL))
        .thenReturn(job);

    IndexingJob result = service.triggerIndexing(library.getId(), caller);

    assertThat(result).isEqualTo(job);
    verify(asyncIndexingExecutor).execute(job.getId(), library, IndexingRunMode.FULL);
    verify(urlIndexingExecutor, never()).execute(any(), any(), any());
    verify(rssFeedIndexingExecutor, never()).execute(any(), any(), any());
  }

  @Test
  void aSystemAdminWithoutAGrantOnAnOrdinaryLibraryIsStillRejected() {
    // ADR-0018, Entscheidung 2: requireRole must be consulted with systemAdmin=false regardless of
    // the caller's real role - a system admin without any grant must not silently gain EDITOR. #521
    // removed the one carve-out that used to exist here (the well-known SYSTEM-owned library,
    // seeded with no owner and no grants, which a system admin could target without a grant) - this
    // also pins that requireRole is now consulted unconditionally, the same as for any other
    // library. #436: no grant at all now answers 404, not 403 - a system admin's own missing grant
    // must not be distinguishable from the library not existing.
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.requireRole(library, systemAdminCaller.id(), false, AssetRole.EDITOR))
        .thenThrow(new NotFoundException("Bibliothek nicht gefunden"));

    assertThatThrownBy(() -> service.triggerIndexing(library.getId(), systemAdminCaller))
        .isInstanceOf(NotFoundException.class);
    verify(indexingJobService, never()).startJob(any(), any(), any(), any());
    verify(libraryAccessService)
        .requireRole(library, systemAdminCaller.id(), false, AssetRole.EDITOR);
    verify(libraryAccessService, never())
        .requireRole(library, systemAdminCaller.id(), true, AssetRole.EDITOR);
  }

  @Test
  void triggerIndexingThrowsConflictWhenAJobIsAlreadyRunningForThisLibrary() {
    stubEditableLibrary();
    when(indexingJobService.isJobRunning(library.getId(), organizationId)).thenReturn(true);

    assertThatThrownBy(() -> service.triggerIndexing(library.getId(), caller))
        .isInstanceOf(ConflictException.class);
    verify(indexingJobService, never()).startJob(any(), any(), any(), any());
  }

  /**
   * #501: a full {@code indexingTaskExecutor} queue must not leave the job row this call just
   * inserted stuck at {@code RUNNING} forever - {@code AbortPolicy} throws {@link
   * TaskRejectedException} synchronously from {@code executor.execute}, and this asserts the job is
   * failed (not left {@code RUNNING}) and the caller gets a 503, not a misleading 202/500.
   */
  @Test
  void triggerIndexingRejectedByAFullQueueFailsTheJobAndReturnsServiceUnavailable() {
    stubEditableLibrary();
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.startJob(
            library.getId(), organizationId, JobTriggerSource.MANUAL, IndexingRunMode.FULL))
        .thenReturn(job);
    doThrow(new TaskRejectedException("queue is full"))
        .when(asyncIndexingExecutor)
        .execute(job.getId(), library, IndexingRunMode.FULL);

    assertThatThrownBy(() -> service.triggerIndexing(library.getId(), caller))
        .isInstanceOf(ServiceUnavailableException.class);
    verify(indexingJobService).failJob(eq(job.getId()), any());
  }

  @Test
  void triggerIndexingOfADifferentLibraryIsNotBlockedByAnUnrelatedRunningJob() {
    // #478 acceptance criteria: concurrency is per library - isJobRunning is only ever asked about
    // *this* library's id, never a global flag.
    stubEditableLibrary();
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.startJob(
            library.getId(), organizationId, JobTriggerSource.MANUAL, IndexingRunMode.FULL))
        .thenReturn(job);
    when(indexingJobService.isJobRunning(library.getId(), organizationId)).thenReturn(false);

    IndexingJob result = service.triggerIndexing(library.getId(), caller);

    assertThat(result).isEqualTo(job);
  }

  @Test
  void anUploadLibraryIsRejectedWithConflictAndNoJobStarts() {
    KnowledgeLibrary uploadLibrary =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Upload-Bibliothek",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false);
    when(libraryRepository.findById(uploadLibrary.getId())).thenReturn(Optional.of(uploadLibrary));
    when(libraryAccessService.requireRole(uploadLibrary, caller.id(), false, AssetRole.EDITOR))
        .thenReturn(AssetRole.EDITOR);

    assertThatThrownBy(() -> service.triggerIndexing(uploadLibrary.getId(), caller))
        .isInstanceOf(ConflictException.class);
    verify(indexingJobService, never()).startJob(any(), any(), any(), any());
    verify(asyncIndexingExecutor, never()).execute(any(), any(), any());
  }

  @Test
  void anHttpDirectoryLibraryStartsTheJobAgainstTheUrlIndexingExecutor() {
    KnowledgeLibrary httpLibrary =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "HTTP-Bibliothek",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.HTTP_DIRECTORY,
            null,
            "https://example.com/files/",
            null,
            null,
            false);
    when(libraryRepository.findById(httpLibrary.getId())).thenReturn(Optional.of(httpLibrary));
    when(libraryAccessService.requireRole(httpLibrary, caller.id(), false, AssetRole.EDITOR))
        .thenReturn(AssetRole.EDITOR);
    var job = new IndexingJob(JobStatus.RUNNING);
    when(urlIndexingExecutor.runModes())
        .thenReturn(Map.of(IndexingRunMode.FULL, VanishedDocumentPolicy.REMOVE_ON_ABSENCE));
    when(indexingJobService.startJob(
            httpLibrary.getId(), organizationId, JobTriggerSource.MANUAL, IndexingRunMode.FULL))
        .thenReturn(job);

    IndexingJob result = service.triggerIndexing(httpLibrary.getId(), caller);

    assertThat(result).isEqualTo(job);
    verify(urlIndexingExecutor).execute(job.getId(), httpLibrary, IndexingRunMode.FULL);
    verify(asyncIndexingExecutor, never()).execute(any(), any(), any());
  }

  @Test
  void anRssFeedLibraryStartsTheJobAgainstTheRssFeedExecutor() {
    KnowledgeLibrary rssLibrary =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "RSS-Bibliothek",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.RSS_FEED,
            null,
            "https://example.com/feed.xml",
            null,
            null,
            false);
    when(libraryRepository.findById(rssLibrary.getId())).thenReturn(Optional.of(rssLibrary));
    when(libraryAccessService.requireRole(rssLibrary, caller.id(), false, AssetRole.EDITOR))
        .thenReturn(AssetRole.EDITOR);
    var job = new IndexingJob(JobStatus.RUNNING);
    // ADR-0023, Entscheidung 4: a one-mode executor runs its declared mode - the run row says so
    when(rssFeedIndexingExecutor.runModes())
        .thenReturn(Map.of(IndexingRunMode.INCREMENTAL, VanishedDocumentPolicy.KEEP_ON_ABSENCE));
    when(indexingJobService.startJob(
            rssLibrary.getId(),
            organizationId,
            JobTriggerSource.MANUAL,
            IndexingRunMode.INCREMENTAL))
        .thenReturn(job);

    IndexingJob result = service.triggerIndexing(rssLibrary.getId(), caller);

    assertThat(result).isEqualTo(job);
    verify(rssFeedIndexingExecutor).execute(job.getId(), rssLibrary, IndexingRunMode.INCREMENTAL);
    verify(asyncIndexingExecutor, never()).execute(any(), any(), any());
    verify(urlIndexingExecutor, never()).execute(any(), any(), any());
  }

  @Test
  void aRequestedRunModeTheExecutorDoesNotDeclareIsRejectedBeforeAnyJobStarts() {
    stubEditableLibrary();
    when(asyncIndexingExecutor.runModes())
        .thenReturn(Map.of(IndexingRunMode.FULL, VanishedDocumentPolicy.REMOVE_ON_ABSENCE));

    assertThatThrownBy(
            () -> service.triggerIndexing(library.getId(), caller, IndexingRunMode.INCREMENTAL))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("INCREMENTAL")
        .hasMessageContaining("FILESYSTEM")
        .hasMessageContaining("FULL");
    verify(indexingJobService, never()).startJob(any(), any(), any(), any());
    verify(asyncIndexingExecutor, never()).execute(any(), any(), any());
  }

  @Test
  void aRequestedRunModeTheExecutorDeclaresIsHandedThroughToTheJobAndTheExecutor() {
    stubEditableLibrary();
    when(asyncIndexingExecutor.runModes())
        .thenReturn(Map.of(IndexingRunMode.FULL, VanishedDocumentPolicy.REMOVE_ON_ABSENCE));
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.startJob(
            library.getId(), organizationId, JobTriggerSource.MANUAL, IndexingRunMode.FULL))
        .thenReturn(job);

    IndexingJob result = service.triggerIndexing(library.getId(), caller, IndexingRunMode.FULL);

    assertThat(result).isEqualTo(job);
    verify(asyncIndexingExecutor).execute(job.getId(), library, IndexingRunMode.FULL);
  }

  @Test
  void aSourceTypeWithoutARegisteredExecutorFailsAtStartupWithAClearErrorInsteadOfAnNpeAtRuntime() {
    assertThatThrownBy(() -> new IndexingSourceExecutorRegistry(List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("FILESYSTEM")
        .hasMessageContaining("HTTP_DIRECTORY")
        .hasMessageContaining("RSS_FEED");
  }

  // --- getStatus ---

  @Test
  void getStatusReturnsEmptyForALibraryThatNeverRan() {
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.requireRole(library, caller.id(), false, AssetRole.VIEWER))
        .thenReturn(AssetRole.VIEWER);
    when(libraryAccessService.canManage(library, caller.id(), false)).thenReturn(false);
    when(indexingJobService.getLatestJob(library.getId(), organizationId))
        .thenReturn(Optional.empty());

    assertThat(service.getStatus(library.getId(), caller).job()).isEmpty();
  }

  @Test
  void getStatusReturnsTheLibrarysLatestJob() {
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.requireRole(library, caller.id(), false, AssetRole.VIEWER))
        .thenReturn(AssetRole.VIEWER);
    when(libraryAccessService.canManage(library, caller.id(), false)).thenReturn(false);
    var job = new IndexingJob(JobStatus.COMPLETED);
    when(indexingJobService.getLatestJob(library.getId(), organizationId))
        .thenReturn(Optional.of(job));

    assertThat(service.getStatus(library.getId(), caller).job()).contains(job);
  }

  @Test
  void getStatusReportsCanSeeErrorDetailOnlyForAManagerOrAbove() {
    // #507/#659: getStatus's caller (LibraryController) decides whether to shorten a FAILED job's
    // raw error message based on this flag - pinned here independently of that shortening so a
    // regression in either place fails the layer it actually belongs to.
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.requireRole(library, caller.id(), false, AssetRole.VIEWER))
        .thenReturn(AssetRole.VIEWER);
    when(indexingJobService.getLatestJob(library.getId(), organizationId))
        .thenReturn(Optional.empty());

    when(libraryAccessService.canManage(library, caller.id(), false)).thenReturn(false);
    assertThat(service.getStatus(library.getId(), caller).canSeeErrorDetail()).isFalse();

    when(libraryAccessService.canManage(library, caller.id(), false)).thenReturn(true);
    assertThat(service.getStatus(library.getId(), caller).canSeeErrorDetail()).isTrue();
  }

  @Test
  void getStatusWithoutAnyGrantFailsWithNotFound() {
    // #436: no grant at all answers 404, not 403 - the same "does not exist" GET
    // /libraries/{id} already answers for the same caller and library.
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.requireRole(library, caller.id(), false, AssetRole.VIEWER))
        .thenThrow(new NotFoundException("Bibliothek nicht gefunden"));

    assertThatThrownBy(() -> service.getStatus(library.getId(), caller))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void getStatusForAForeignLibraryFailsWithNotFound() {
    KnowledgeLibrary foreignLibrary =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Fremde Bibliothek",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false);
    when(libraryRepository.findById(foreignLibrary.getId()))
        .thenReturn(Optional.of(foreignLibrary));

    assertThatThrownBy(() -> service.getStatus(foreignLibrary.getId(), caller))
        .isInstanceOf(NotFoundException.class);
  }

  // --- getRecentRuns (#513, PR #604 review finding 1) ---

  @Test
  void getRecentRunsWithManageAccessReturnsTheLibrarysRuns() {
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.canManage(library, caller.id(), false)).thenReturn(true);
    var job = new IndexingJob(JobStatus.COMPLETED);
    when(indexingJobService.getRecentJobs(library.getId(), organizationId))
        .thenReturn(List.of(job));
    when(indexingRunEventRepository.findByJobIdOrderByCreatedAtAsc(job.getId()))
        .thenReturn(List.of());

    var runs = service.getRecentRuns(library.getId(), caller);

    assertThat(runs).hasSize(1);
    assertThat(runs.getFirst().job()).isEqualTo(job);
  }

  /**
   * A mere {@code VIEWER} (only {@code canRead}, not {@code canManage}) must not see the run
   * protocol - {@link IndexingRunEvent#getReference()} routinely carries the library's own {@code
   * sourcePath}/{@code sourceUrl}, exactly the internal-path leak #507 exists to close for the
   * source configuration display itself (PR #604 review, finding 1). Kept as its own test distinct
   * from {@link #getStatusWithoutReadAccessFailsWithForbidden} - that one only proves the
   * *narrower* {@code canRead} bar is enforced; this one proves the *stricter* {@code canManage}
   * bar applies here even when {@code canRead} would have passed.
   */
  @Test
  void getRecentRunsWithOnlyReadAccessFailsWithForbidden() {
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.canManage(library, caller.id(), false)).thenReturn(false);

    assertThatThrownBy(() -> service.getRecentRuns(library.getId(), caller))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void getRecentRunsForAForeignLibraryFailsWithNotFound() {
    KnowledgeLibrary foreignLibrary =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Fremde Bibliothek",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false);
    when(libraryRepository.findById(foreignLibrary.getId()))
        .thenReturn(Optional.of(foreignLibrary));

    assertThatThrownBy(() -> service.getRecentRuns(foreignLibrary.getId(), caller))
        .isInstanceOf(NotFoundException.class);
  }
}
