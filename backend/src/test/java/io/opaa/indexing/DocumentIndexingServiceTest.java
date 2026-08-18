package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.dto.IndexingTriggerRequest;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.library.LibraryVisibility;
import java.net.URI;
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
 * #419: triggering an indexing run always requires a caller-chosen, authorized target library -
 * this pins the resolution/authorization logic in {@link DocumentIndexingService}. ADR-0017:
 * additionally pins source-type resolution (explicit field vs. backward-compatible fallback), the
 * contradiction check, and delegation through {@link IndexingSourceExecutorRegistry} - the
 * controller and both executors delegate to this service entirely.
 */
@ExtendWith(MockitoExtension.class)
class DocumentIndexingServiceTest {

  @Mock private IndexingJobService indexingJobService;
  @Mock private SourceIndexingExecutor asyncIndexingExecutor;
  @Mock private SourceIndexingExecutor urlIndexingExecutor;
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
    var registry =
        new IndexingSourceExecutorRegistry(List.of(asyncIndexingExecutor, urlIndexingExecutor));
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
            false);
  }

  @Test
  void triggerIndexingWithoutLibraryIdFailsWithBadRequestAndDoesNotStartAJob() {
    // #419 acceptance criteria: no libraryId -> 400, German message, no run started.
    assertThatThrownBy(() -> service.triggerIndexing((UUID) null, currentUser.getId(), false))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> {
              assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(400));
              assertThat(ex.getReason()).isEqualTo("libraryId ist erforderlich");
            });
    verify(indexingJobService, never()).startJob(any());
    verify(asyncIndexingExecutor, never()).execute(any(), any(), any());
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
    verify(asyncIndexingExecutor, never()).execute(any(), any(), any());
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
    verify(asyncIndexingExecutor)
        .execute(eq(job.getId()), any(IndexingTriggerRequest.class), eq(library));
  }

  @Test
  void aSystemAdminWithoutAGrantOnAnOrdinaryLibraryIsStillRejected() {
    // PR #431 review, Befund 2: POST /api/v1/indexing/trigger already requires SYSTEM_ADMIN, so
    // every caller reaching this method has systemAdmin=true - bypassing the EDITOR check for
    // that flag too would make the 403 branch unreachable in practice, letting any system admin
    // write into a library (e.g. another person's private "Meine Dokumente") they were never
    // granted. canEdit must be consulted with systemAdmin=false, not the caller's real value.
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
  void aSystemAdminMayTargetTheSystemLibraryWithoutAnExplicitGrant() {
    // The system library is seeded with no owner and no grants (migration 012) - under the
    // ordinary EDITOR formula nobody, not even a system admin, could ever target it, which would
    // silently strand the one path that still writes there today. This is the one deliberate
    // carve-out from the rule above.
    KnowledgeLibrary systemLibrary = mock(KnowledgeLibrary.class);
    UUID systemLibraryId = UUID.randomUUID();
    when(systemLibrary.getId()).thenReturn(systemLibraryId);
    when(systemLibrary.getOrganizationId()).thenReturn(organizationId);
    when(systemLibrary.isSystemLibrary()).thenReturn(true);
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(systemLibraryId)).thenReturn(Optional.of(systemLibrary));
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.startJob(systemLibraryId)).thenReturn(job);

    IndexingJob result = service.triggerIndexing(systemLibraryId, currentUser.getId(), true);

    assertThat(result).isEqualTo(job);
    verify(libraryAccessService, never())
        .canEdit(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void aNonSystemAdminMayNotTargetTheSystemLibraryWithoutAGrant() {
    KnowledgeLibrary systemLibrary = mock(KnowledgeLibrary.class);
    UUID systemLibraryId = UUID.randomUUID();
    when(systemLibrary.getOrganizationId()).thenReturn(organizationId);
    // systemAdmin=false short-circuits "systemAdmin && library.isSystemLibrary()" before
    // isSystemLibrary() is ever called, so it is deliberately left unstubbed (Mockito's
    // UnnecessaryStubbingException would otherwise fail this test).
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(systemLibraryId)).thenReturn(Optional.of(systemLibrary));
    when(libraryAccessService.canEdit(systemLibrary, currentUser.getId(), false)).thenReturn(false);

    assertThatThrownBy(() -> service.triggerIndexing(systemLibraryId, currentUser.getId(), false))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(403)));
  }

  @Test
  void triggerIndexingThrowsWhenAJobIsAlreadyRunningBeforeCheckingTheLibrary() {
    when(indexingJobService.isJobRunning()).thenReturn(true);

    assertThatThrownBy(() -> service.triggerIndexing(library.getId(), currentUser.getId(), false))
        .isInstanceOf(IndexingAlreadyRunningException.class);
    verify(userRepository, never()).findById(any());
  }

  // --- ADR-0017: explicit sourceType, fallback derivation and contradiction checks ---
  //
  // The old triggerUrlIndexing(UrlIndexingRequest, ...) convenience method was removed - it had
  // no production caller left once IndexingController started calling the unified
  // triggerIndexing(IndexingTriggerRequest, ...) directly. Its scenarios (no libraryId, a
  // successful URL run, a blank URL) live on below, expressed through that unified method.

  @Test
  void triggerIndexingWithAnHttpDirectoryRequestWithoutLibraryIdFailsWithBadRequest() {
    var request =
        new IndexingTriggerRequest()
            .sourceType(IndexingSourceType.HTTP_DIRECTORY)
            .url(URI.create("https://example.com/files/"));

    assertThatThrownBy(() -> service.triggerIndexing(request, currentUser.getId(), false))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(400)));
    verify(urlIndexingExecutor, never()).execute(any(), any(), any());
  }

  @Test
  void triggerIndexingWithAnExplicitSourceTypeSkipsTheFallbackDerivation() {
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.canEdit(library, currentUser.getId(), false)).thenReturn(true);
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.startJob(library.getId())).thenReturn(job);
    var request =
        new IndexingTriggerRequest()
            .libraryId(library.getId())
            .sourceType(IndexingSourceType.FILESYSTEM);

    IndexingJob result = service.triggerIndexing(request, currentUser.getId(), false);

    assertThat(result).isEqualTo(job);
    verify(asyncIndexingExecutor).execute(job.getId(), request, library);
    verify(urlIndexingExecutor, never()).execute(any(), any(), any());
  }

  @Test
  void triggerIndexingWithoutSourceTypeButWithAUrlFallsBackToHttpDirectory() {
    // ADR-0017, decision 1: the backward-compatible fallback still applies when sourceType is
    // absent - url present means HTTP_DIRECTORY, exactly as before this ADR.
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.canEdit(library, currentUser.getId(), false)).thenReturn(true);
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.startJob(library.getId())).thenReturn(job);
    var request =
        new IndexingTriggerRequest()
            .libraryId(library.getId())
            .url(URI.create("https://example.com/files/"));

    IndexingJob result = service.triggerIndexing(request, currentUser.getId(), false);

    assertThat(result).isEqualTo(job);
    verify(urlIndexingExecutor).execute(job.getId(), request, library);
    verify(asyncIndexingExecutor, never()).execute(any(), any(), any());
  }

  @Test
  void triggerIndexingWithoutSourceTypeAndWithoutAUrlFallsBackToFilesystem() {
    when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryAccessService.canEdit(library, currentUser.getId(), false)).thenReturn(true);
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.startJob(library.getId())).thenReturn(job);
    var request = new IndexingTriggerRequest().libraryId(library.getId());

    IndexingJob result = service.triggerIndexing(request, currentUser.getId(), false);

    assertThat(result).isEqualTo(job);
    verify(asyncIndexingExecutor).execute(job.getId(), request, library);
    verify(urlIndexingExecutor, never()).execute(any(), any(), any());
  }

  @Test
  void anHttpDirectoryRequestWithoutAUrlIsRejectedWithAGermanMessage() {
    // ADR-0017 acceptance criteria: a source type that needs an address but got none is rejected
    // before a job is started - a run that would find nothing must never start.
    var request =
        new IndexingTriggerRequest()
            .libraryId(library.getId())
            .sourceType(IndexingSourceType.HTTP_DIRECTORY);

    assertThatThrownBy(() -> service.triggerIndexing(request, currentUser.getId(), false))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> {
              assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(400));
              assertThat(ex.getReason())
                  .isEqualTo("Der Quellentyp HTTP_DIRECTORY erfordert eine URL");
            });
    verify(indexingJobService, never()).startJob(any());
  }

  @Test
  void aFilesystemRequestWithAUrlIsRejectedWithAGermanMessage() {
    // ADR-0017 acceptance criteria: the reverse contradiction is rejected too - a field the
    // caller set would silently be ignored otherwise.
    var request =
        new IndexingTriggerRequest()
            .libraryId(library.getId())
            .sourceType(IndexingSourceType.FILESYSTEM)
            .url(URI.create("https://example.com/files/"));

    assertThatThrownBy(() -> service.triggerIndexing(request, currentUser.getId(), false))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> {
              assertThat(ex.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(400));
              assertThat(ex.getReason())
                  .isEqualTo("Der Quellentyp FILESYSTEM darf keine URL enthalten");
            });
    verify(indexingJobService, never()).startJob(any());
  }

  @Test
  void aSourceTypeWithoutARegisteredExecutorFailsAtStartupWithAClearErrorInsteadOfAnNpeAtRuntime() {
    // ADR-0017 acceptance criteria: a source type without a matching executor is a clear
    // rejection, not a NullPointerException reached through some later HTTP request - the
    // registry now checks completeness in its constructor, so the failure happens at application
    // startup instead.
    assertThatThrownBy(() -> new IndexingSourceExecutorRegistry(List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("FILESYSTEM")
        .hasMessageContaining("HTTP_DIRECTORY");
  }
}
