package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.library.LibraryVisibility;
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
 * #419: triggering an indexing run always requires a caller-chosen, authorized target library -
 * this pins the resolution/authorization logic in {@link DocumentIndexingService}, which the
 * controller and both executors delegate to entirely.
 */
@ExtendWith(MockitoExtension.class)
class DocumentIndexingServiceTest {

  @Mock private IndexingJobService indexingJobService;
  @Mock private AsyncIndexingExecutor asyncIndexingExecutor;
  @Mock private UrlIndexingExecutor urlIndexingExecutor;
  @Mock private UserRepository userRepository;
  @Mock private KnowledgeLibraryRepository libraryRepository;
  @Mock private LibraryAccessService libraryAccessService;

  private DocumentIndexingService service;

  private final UUID organizationId = UUID.randomUUID();
  private User currentUser;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    service =
        new DocumentIndexingService(
            indexingJobService,
            asyncIndexingExecutor,
            urlIndexingExecutor,
            userRepository,
            libraryRepository,
            libraryAccessService);

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
            false);
  }

  @Test
  void triggerIndexingWithoutLibraryIdFailsWithBadRequestAndDoesNotStartAJob() {
    // #419 acceptance criteria: no libraryId -> 400, German message, no run started.
    assertThatThrownBy(() -> service.triggerIndexing(null, currentUser.getId(), false))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> {
              assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(400));
              assertThat(ex.getReason()).isEqualTo("libraryId ist erforderlich");
            });
    verify(indexingJobService, never()).startJob(any());
    verify(asyncIndexingExecutor, never()).execute(any(), any());
  }

  @Test
  void triggerIndexingWithAViewerOnlyGrantFailsWithForbiddenAndDoesNotStartAJob() {
    // #419 acceptance criteria: caller with only VIEWER on the target library -> 403, no run.
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
    // #419 acceptance criteria: a library belonging to a foreign organization must not be
    // distinguishable from one that does not exist at all.
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
  void triggerIndexingWithAnEditorGrantStartsTheJobAgainstTheChosenLibrary() {
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.canEdit(library, currentUser.getId(), false)).thenReturn(true);
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.startJob(library.getId())).thenReturn(job);

    IndexingJob result = service.triggerIndexing(library.getId(), currentUser.getId(), false);

    assertThat(result).isEqualTo(job);
    verify(asyncIndexingExecutor).execute(job.getId(), library);
  }

  @Test
  void triggerIndexingBypassesTheRoleCheckForASystemAdmin() {
    // Mirrors LibraryAccessService#effectiveRole's system-admin bypass, which every other
    // library operation already relies on - the SYSTEM_ADMIN @PreAuthorize on the controller
    // stays in place alongside this check, not instead of it.
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.canEdit(library, currentUser.getId(), true)).thenReturn(true);
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.startJob(library.getId())).thenReturn(job);

    IndexingJob result = service.triggerIndexing(library.getId(), currentUser.getId(), true);

    assertThat(result).isEqualTo(job);
  }

  @Test
  void triggerIndexingThrowsWhenAJobIsAlreadyRunningBeforeCheckingTheLibrary() {
    when(indexingJobService.isJobRunning()).thenReturn(true);

    assertThatThrownBy(() -> service.triggerIndexing(library.getId(), currentUser.getId(), false))
        .isInstanceOf(IndexingAlreadyRunningException.class);
    verify(userRepository, never()).findById(any());
  }

  @Test
  void triggerUrlIndexingWithoutLibraryIdFailsWithBadRequest() {
    var request = new UrlIndexingRequest("https://example.com/files/", null, null, false);

    assertThatThrownBy(() -> service.triggerUrlIndexing(request, null, currentUser.getId(), false))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(400)));
    verify(urlIndexingExecutor, never()).execute(any(), any(), any());
  }

  @Test
  void triggerUrlIndexingWithAnEditorGrantStartsTheJobAgainstTheChosenLibrary() {
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.canEdit(library, currentUser.getId(), false)).thenReturn(true);
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.startJob(library.getId())).thenReturn(job);
    var request = new UrlIndexingRequest("https://example.com/files/", null, null, false);

    IndexingJob result =
        service.triggerUrlIndexing(request, library.getId(), currentUser.getId(), false);

    assertThat(result).isEqualTo(job);
    verify(urlIndexingExecutor).execute(job.getId(), request, library);
  }

  @Test
  void triggerUrlIndexingWithBlankUrlFailsBeforeCheckingTheLibrary() {
    var request = new UrlIndexingRequest("   ", null, null, false);

    assertThatThrownBy(
            () -> service.triggerUrlIndexing(request, library.getId(), currentUser.getId(), false))
        .isInstanceOf(IllegalArgumentException.class);
    verify(userRepository, never()).findById(any());
  }
}
