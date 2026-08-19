package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
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
            indexingJobService, registry, userRepository, libraryRepository, libraryAccessService);

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
    when(libraryAccessService.canEdit(library, currentUser.getId(), false)).thenReturn(true);
  }

  @Test
  void triggerIndexingWithAViewerOnlyGrantFailsWithForbiddenAndDoesNotStartAJob() {
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.canEdit(library, currentUser.getId(), false)).thenReturn(false);

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
            false,
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
    // ADR-0018, Entscheidung 2: canEdit must be consulted with systemAdmin=false regardless of the
    // caller's real role - a system admin without any grant must not silently gain EDITOR.
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.canEdit(library, currentUser.getId(), false)).thenReturn(false);

    assertThatThrownBy(() -> service.triggerIndexing(library.getId(), currentUser.getId(), true))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(403)));
    verify(indexingJobService, never()).startJob(any());
    verify(libraryAccessService, never()).canEdit(library, currentUser.getId(), true);
  }

  @Test
  void aSystemAdminWithoutAGrantIsRejectedEvenWithNoOwnerColumnsSet() {
    // #521 removed the one carve-out that used to exist here (the well-known SYSTEM-owned
    // library, seeded with no owner and no grants, which a system admin could target without a
    // grant) - this pins that canEdit is now consulted unconditionally, the same as for any other
    // library, even one whose mock stubs nothing beyond organization membership.
    KnowledgeLibrary otherLibrary = mock(KnowledgeLibrary.class);
    UUID otherLibraryId = UUID.randomUUID();
    when(otherLibrary.getOrganizationId()).thenReturn(organizationId);
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(otherLibraryId)).thenReturn(Optional.of(otherLibrary));
    when(libraryAccessService.canEdit(otherLibrary, currentUser.getId(), false)).thenReturn(false);

    assertThatThrownBy(() -> service.triggerIndexing(otherLibraryId, currentUser.getId(), true))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(403)));
    verify(libraryAccessService).canEdit(otherLibrary, currentUser.getId(), false);
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
            false,
            false);
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(uploadLibrary.getId())).thenReturn(Optional.of(uploadLibrary));
    when(libraryAccessService.canEdit(uploadLibrary, currentUser.getId(), false)).thenReturn(true);

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
            false,
            DocumentSourceType.HTTP_DIRECTORY,
            null,
            "https://example.com/files/",
            null,
            null,
            false);
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(httpLibrary.getId())).thenReturn(Optional.of(httpLibrary));
    when(libraryAccessService.canEdit(httpLibrary, currentUser.getId(), false)).thenReturn(true);
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
            false,
            DocumentSourceType.RSS_FEED,
            null,
            "https://example.com/feed.xml",
            null,
            null,
            false);
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(rssLibrary.getId())).thenReturn(Optional.of(rssLibrary));
    when(libraryAccessService.canEdit(rssLibrary, currentUser.getId(), false)).thenReturn(true);
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
    when(libraryAccessService.canRead(library, currentUser.getId(), false)).thenReturn(true);
    when(indexingJobService.getLatestJob(library.getId())).thenReturn(Optional.empty());

    assertThat(service.getStatus(library.getId(), currentUser.getId(), false)).isEmpty();
  }

  @Test
  void getStatusReturnsTheLibrarysLatestJob() {
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.canRead(library, currentUser.getId(), false)).thenReturn(true);
    var job = new IndexingJob(JobStatus.COMPLETED);
    when(indexingJobService.getLatestJob(library.getId())).thenReturn(Optional.of(job));

    assertThat(service.getStatus(library.getId(), currentUser.getId(), false)).contains(job);
  }

  @Test
  void getStatusWithoutReadAccessFailsWithForbidden() {
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.canRead(library, currentUser.getId(), false)).thenReturn(false);

    assertThatThrownBy(() -> service.getStatus(library.getId(), currentUser.getId(), false))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(403)));
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
            false,
            false);
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(foreignLibrary.getId()))
        .thenReturn(Optional.of(foreignLibrary));

    assertThatThrownBy(() -> service.getStatus(foreignLibrary.getId(), currentUser.getId(), false))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(404)));
  }
}
