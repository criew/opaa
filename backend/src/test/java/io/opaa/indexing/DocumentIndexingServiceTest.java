package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.library.AssetRole;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.library.LibraryVisibility;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

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
  @Mock private UserRepository userRepository;
  @Mock private KnowledgeLibraryRepository libraryRepository;
  @Mock private LibraryAccessService libraryAccessService;
  @Mock private IndexingRunEventRepository indexingRunEventRepository;

  private DocumentIndexingService service;

  private final UUID organizationId = UUID.randomUUID();
  private User currentUser;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    when(asyncIndexingExecutor.sourceType()).thenReturn(IndexingSourceType.FILESYSTEM);
    when(urlIndexingExecutor.sourceType()).thenReturn(IndexingSourceType.HTTP_DIRECTORY);
    when(rssFeedIndexingExecutor.sourceType()).thenReturn(IndexingSourceType.RSS_FEED);
    var registry =
        new IndexingSourceExecutorRegistry(
            List.of(asyncIndexingExecutor, urlIndexingExecutor, rssFeedIndexingExecutor));
    service =
        new DocumentIndexingService(
            indexingJobService,
            registry,
            userRepository,
            libraryRepository,
            libraryAccessService,
            indexingRunEventRepository);

    currentUser = new User("subject", "issuer", "user@example.com", "Test User");
    currentUser.setOrganizationId(organizationId);
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
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.requireRole(library, currentUser.getId(), false, AssetRole.EDITOR))
        .thenReturn(AssetRole.EDITOR);
  }

  @Test
  void triggerIndexingWithAViewerOnlyGrantFailsWithForbiddenAndDoesNotStartAJob() {
    // Some access (a real VIEWER grant), just not enough - #436 keeps this at 403, distinct from
    // "no access at all" (see aSystemAdminWithoutAGrantOnAnOrdinaryLibraryIsStillRejected below).
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.requireRole(library, currentUser.getId(), false, AssetRole.EDITOR))
        .thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek"));

    assertThatThrownBy(() -> service.triggerIndexing(library.getId(), currentUser.getId(), false))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(403)));
    verify(indexingJobService, never()).startJob(any());
    verify(asyncIndexingExecutor, never()).execute(any(), any());
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
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(foreignLibrary.getId()))
        .thenReturn(Optional.of(foreignLibrary));

    assertThatThrownBy(
            () -> service.triggerIndexing(foreignLibrary.getId(), currentUser.getId(), false))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> {
              assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(404));
              assertThat(ex.getReason()).isEqualTo("Bibliothek nicht gefunden");
            });
    verify(indexingJobService, never()).startJob(any());
  }

  @Test
  void triggerIndexingWithAnUnknownLibraryFailsWithNotFound() {
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    UUID unknownLibraryId = UUID.randomUUID();
    when(libraryRepository.findById(unknownLibraryId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.triggerIndexing(unknownLibraryId, currentUser.getId(), false))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(404)));
  }

  @Test
  void triggerIndexingWithAnEditorGrantStartsTheJobAgainstTheLibrarysOwnConfiguration() {
    stubEditableLibrary();
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.startJob(library.getId())).thenReturn(job);

    IndexingJob result = service.triggerIndexing(library.getId(), currentUser.getId(), false);

    assertThat(result).isEqualTo(job);
    verify(asyncIndexingExecutor).execute(job.getId(), library);
    verify(urlIndexingExecutor, never()).execute(any(), any());
    verify(rssFeedIndexingExecutor, never()).execute(any(), any());
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
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.requireRole(library, currentUser.getId(), false, AssetRole.EDITOR))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Bibliothek nicht gefunden"));

    assertThatThrownBy(() -> service.triggerIndexing(library.getId(), currentUser.getId(), true))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(404)));
    verify(indexingJobService, never()).startJob(any());
    verify(libraryAccessService).requireRole(library, currentUser.getId(), false, AssetRole.EDITOR);
    verify(libraryAccessService, never())
        .requireRole(library, currentUser.getId(), true, AssetRole.EDITOR);
  }

  @Test
  void triggerIndexingThrowsConflictWhenAJobIsAlreadyRunningForThisLibrary() {
    stubEditableLibrary();
    when(indexingJobService.isJobRunning(library.getId())).thenReturn(true);

    assertThatThrownBy(() -> service.triggerIndexing(library.getId(), currentUser.getId(), false))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(409)));
    verify(indexingJobService, never()).startJob(any());
  }

  @Test
  void triggerIndexingOfADifferentLibraryIsNotBlockedByAnUnrelatedRunningJob() {
    // #478 acceptance criteria: concurrency is per library - isJobRunning is only ever asked about
    // *this* library's id, never a global flag.
    stubEditableLibrary();
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.startJob(library.getId())).thenReturn(job);
    when(indexingJobService.isJobRunning(library.getId())).thenReturn(false);

    IndexingJob result = service.triggerIndexing(library.getId(), currentUser.getId(), false);

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
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(uploadLibrary.getId())).thenReturn(Optional.of(uploadLibrary));
    when(libraryAccessService.requireRole(
            uploadLibrary, currentUser.getId(), false, AssetRole.EDITOR))
        .thenReturn(AssetRole.EDITOR);

    assertThatThrownBy(
            () -> service.triggerIndexing(uploadLibrary.getId(), currentUser.getId(), false))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(409)));
    verify(indexingJobService, never()).startJob(any());
    verify(asyncIndexingExecutor, never()).execute(any(), any());
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
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(httpLibrary.getId())).thenReturn(Optional.of(httpLibrary));
    when(libraryAccessService.requireRole(
            httpLibrary, currentUser.getId(), false, AssetRole.EDITOR))
        .thenReturn(AssetRole.EDITOR);
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.startJob(httpLibrary.getId())).thenReturn(job);

    IndexingJob result = service.triggerIndexing(httpLibrary.getId(), currentUser.getId(), false);

    assertThat(result).isEqualTo(job);
    verify(urlIndexingExecutor).execute(job.getId(), httpLibrary);
    verify(asyncIndexingExecutor, never()).execute(any(), any());
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
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(rssLibrary.getId())).thenReturn(Optional.of(rssLibrary));
    when(libraryAccessService.requireRole(rssLibrary, currentUser.getId(), false, AssetRole.EDITOR))
        .thenReturn(AssetRole.EDITOR);
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.startJob(rssLibrary.getId())).thenReturn(job);

    IndexingJob result = service.triggerIndexing(rssLibrary.getId(), currentUser.getId(), false);

    assertThat(result).isEqualTo(job);
    verify(rssFeedIndexingExecutor).execute(job.getId(), rssLibrary);
    verify(asyncIndexingExecutor, never()).execute(any(), any());
    verify(urlIndexingExecutor, never()).execute(any(), any());
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
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.requireRole(library, currentUser.getId(), false, AssetRole.VIEWER))
        .thenReturn(AssetRole.VIEWER);
    when(libraryAccessService.canManage(library, currentUser.getId(), false)).thenReturn(false);
    when(indexingJobService.getLatestJob(library.getId())).thenReturn(Optional.empty());

    assertThat(service.getStatus(library.getId(), currentUser.getId(), false).job()).isEmpty();
  }

  @Test
  void getStatusReturnsTheLibrarysLatestJob() {
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.requireRole(library, currentUser.getId(), false, AssetRole.VIEWER))
        .thenReturn(AssetRole.VIEWER);
    when(libraryAccessService.canManage(library, currentUser.getId(), false)).thenReturn(false);
    var job = new IndexingJob(JobStatus.COMPLETED);
    when(indexingJobService.getLatestJob(library.getId())).thenReturn(Optional.of(job));

    assertThat(service.getStatus(library.getId(), currentUser.getId(), false).job()).contains(job);
  }

  @Test
  void getStatusReportsCanSeeErrorDetailOnlyForAManagerOrAbove() {
    // #507/#659: getStatus's caller (LibraryController) decides whether to shorten a FAILED job's
    // raw error message based on this flag - pinned here independently of that shortening so a
    // regression in either place fails the layer it actually belongs to.
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.requireRole(library, currentUser.getId(), false, AssetRole.VIEWER))
        .thenReturn(AssetRole.VIEWER);
    when(indexingJobService.getLatestJob(library.getId())).thenReturn(Optional.empty());

    when(libraryAccessService.canManage(library, currentUser.getId(), false)).thenReturn(false);
    assertThat(service.getStatus(library.getId(), currentUser.getId(), false).canSeeErrorDetail())
        .isFalse();

    when(libraryAccessService.canManage(library, currentUser.getId(), false)).thenReturn(true);
    assertThat(service.getStatus(library.getId(), currentUser.getId(), false).canSeeErrorDetail())
        .isTrue();
  }

  @Test
  void getStatusWithoutAnyGrantFailsWithNotFound() {
    // #436: no grant at all answers 404, not 403 - the same "does not exist" GET
    // /libraries/{id} already answers for the same caller and library.
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.requireRole(library, currentUser.getId(), false, AssetRole.VIEWER))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Bibliothek nicht gefunden"));

    assertThatThrownBy(() -> service.getStatus(library.getId(), currentUser.getId(), false))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(404)));
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
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(foreignLibrary.getId()))
        .thenReturn(Optional.of(foreignLibrary));

    assertThatThrownBy(() -> service.getStatus(foreignLibrary.getId(), currentUser.getId(), false))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(404)));
  }

  // --- getRecentRuns (#513, PR #604 review finding 1) ---

  @Test
  void getRecentRunsWithManageAccessReturnsTheLibrarysRuns() {
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.canManage(library, currentUser.getId(), false)).thenReturn(true);
    var job = new IndexingJob(JobStatus.COMPLETED);
    when(indexingJobService.getRecentJobs(library.getId())).thenReturn(List.of(job));
    when(indexingRunEventRepository.findByJobIdOrderByCreatedAtAsc(job.getId()))
        .thenReturn(List.of());

    var runs = service.getRecentRuns(library.getId(), currentUser.getId(), false);

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
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.canManage(library, currentUser.getId(), false)).thenReturn(false);

    assertThatThrownBy(() -> service.getRecentRuns(library.getId(), currentUser.getId(), false))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(403)));
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
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(foreignLibrary.getId()))
        .thenReturn(Optional.of(foreignLibrary));

    assertThatThrownBy(
            () -> service.getRecentRuns(foreignLibrary.getId(), currentUser.getId(), false))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(404)));
  }
}
