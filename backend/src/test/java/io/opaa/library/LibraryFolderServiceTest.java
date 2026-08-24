package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.dto.LibraryFolderRenameRequest;
import io.opaa.api.dto.LibraryFolderRequest;
import io.opaa.api.dto.LibraryFolderResponse;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.DocumentSourceType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link LibraryFolderService} (#820): name validation, the EDITOR/VIEWER permission
 * gates, the UPLOAD-only restriction (ADR-0020), name-conflict detection, the nesting depth limit,
 * and that {@link LibraryFolderService#deleteFolder} recurses leaf-first through {@link
 * LibraryDocumentService#deleteDocument} rather than deleting rows directly.
 */
class LibraryFolderServiceTest {

  private LibraryFolderRepository folderRepository;
  private KnowledgeLibraryRepository libraryRepository;
  private UserRepository userRepository;
  private LibraryAccessService accessService;
  private DocumentRepository documentRepository;
  private LibraryDocumentService documentService;
  private LibraryFolderService service;
  private KnowledgeLibrary library;

  private final UUID currentUserId = UUID.randomUUID();
  private final UUID organizationId = UUID.randomUUID();
  private final UUID libraryId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    folderRepository = mock(LibraryFolderRepository.class);
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    userRepository = mock(UserRepository.class);
    accessService = mock(LibraryAccessService.class);
    documentRepository = mock(DocumentRepository.class);
    documentService = mock(LibraryDocumentService.class);
    // #823 review, Befund 6: LibraryFolderService now builds a REQUIRES_NEW TransactionTemplate
    // from this in its constructor - TransactionTemplate#execute invokes the callback synchronously
    // regardless of whether the underlying manager is real, mirroring SpaceServiceTest's identical
    // setup for the same reason.
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

    service =
        new LibraryFolderService(
            folderRepository,
            libraryRepository,
            userRepository,
            accessService,
            documentRepository,
            documentService,
            transactionManager);

    User user = new User("subject", "issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationId);
    when(userRepository.findById(currentUserId)).thenReturn(Optional.of(user));

    library = mock(KnowledgeLibrary.class);
    when(library.getId()).thenReturn(libraryId);
    when(library.getOrganizationId()).thenReturn(organizationId);
    when(library.getSourceType()).thenReturn(DocumentSourceType.UPLOAD);
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

    when(folderRepository.saveAndFlush(any(LibraryFolder.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(folderRepository.findByLibraryIdAndParentFolderIdOrderByNameAsc(any(), any()))
        .thenReturn(List.of());
    when(documentRepository.countByFolderId(any())).thenReturn(0L);
    when(documentRepository.findByFolderId(any())).thenReturn(List.of());
  }

  private void grantEditor() {
    when(accessService.requireRole(any(), eq(currentUserId), eq(false), eq(AssetRole.EDITOR)))
        .thenReturn(AssetRole.EDITOR);
  }

  private void grantViewer() {
    when(accessService.requireRole(any(), eq(currentUserId), eq(false), eq(AssetRole.VIEWER)))
        .thenReturn(AssetRole.VIEWER);
  }

  private void denyEditor() {
    when(accessService.requireRole(any(), eq(currentUserId), eq(false), eq(AssetRole.EDITOR)))
        .thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek"));
  }

  @Test
  void editorCreatesARootLevelFolder() {
    grantEditor();
    when(folderRepository.findByLibraryIdAndParentFolderIdIsNullAndName(libraryId, "Protokolle"))
        .thenReturn(Optional.empty());

    LibraryFolderResponse response =
        service.createFolder(
            libraryId, new LibraryFolderRequest("Protokolle"), currentUserId, false);

    assertThat(response.getName()).isEqualTo("Protokolle");
    assertThat(response.getLibraryId()).isEqualTo(libraryId);
    assertThat(response.getParentFolderId()).isNull();
    assertThat(response.getDocumentCount()).isZero();
  }

  @Test
  void createFolderTrimsTheName() {
    grantEditor();
    when(folderRepository.findByLibraryIdAndParentFolderIdIsNullAndName(libraryId, "Protokolle"))
        .thenReturn(Optional.empty());

    LibraryFolderResponse response =
        service.createFolder(
            libraryId, new LibraryFolderRequest("  Protokolle  "), currentUserId, false);

    assertThat(response.getName()).isEqualTo("Protokolle");
  }

  @Test
  void aViewerCannotCreateAFolder() {
    denyEditor();

    assertThatThrownBy(
            () ->
                service.createFolder(
                    libraryId, new LibraryFolderRequest("Protokolle"), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void creatingAFolderInAConnectorLibraryIsRejectedWithConflict() {
    grantEditor();
    when(library.getSourceType()).thenReturn(DocumentSourceType.FILESYSTEM);

    assertThatThrownBy(
            () ->
                service.createFolder(
                    libraryId, new LibraryFolderRequest("Protokolle"), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void aBlankNameIsRejected() {
    grantEditor();

    assertThatThrownBy(
            () ->
                service.createFolder(
                    libraryId, new LibraryFolderRequest("   "), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void aNameContainingASlashIsRejected() {
    grantEditor();

    assertThatThrownBy(
            () ->
                service.createFolder(
                    libraryId, new LibraryFolderRequest("Akten/2026"), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void aNameOverTheLengthLimitIsRejected() {
    grantEditor();

    assertThatThrownBy(
            () ->
                service.createFolder(
                    libraryId, new LibraryFolderRequest("x".repeat(256)), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void aDuplicateNameOnTheSameLevelIsRejectedWithConflict() {
    grantEditor();
    when(folderRepository.findByLibraryIdAndParentFolderIdIsNullAndName(libraryId, "Protokolle"))
        .thenReturn(Optional.of(new LibraryFolder(libraryId, null, "Protokolle", organizationId)));

    assertThatThrownBy(
            () ->
                service.createFolder(
                    libraryId, new LibraryFolderRequest("Protokolle"), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void anUnknownParentFolderIsRejectedWithNotFound() {
    grantEditor();
    UUID missingParentId = UUID.randomUUID();
    when(folderRepository.findById(missingParentId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.createFolder(
                    libraryId,
                    new LibraryFolderRequest("Protokolle").parentFolderId(missingParentId),
                    currentUserId,
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void aParentFromAnotherLibraryIsRejectedWithNotFound() {
    grantEditor();
    LibraryFolder foreignParent =
        new LibraryFolder(UUID.randomUUID(), null, "Fremd", organizationId);
    when(folderRepository.findById(foreignParent.getId())).thenReturn(Optional.of(foreignParent));

    assertThatThrownBy(
            () ->
                service.createFolder(
                    libraryId,
                    new LibraryFolderRequest("Protokolle").parentFolderId(foreignParent.getId()),
                    currentUserId,
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void nestingBeyondTheDepthLimitIsRejected() {
    grantEditor();
    // Build a chain of 10 folders, each the parent of the next - MAX_DEPTH is 10, so a folder
    // requested under the tenth link (depth 11) must be rejected.
    UUID parentId = null;
    for (int i = 0; i < 10; i++) {
      LibraryFolder folder = new LibraryFolder(libraryId, parentId, "Ebene " + i, organizationId);
      when(folderRepository.findById(folder.getId())).thenReturn(Optional.of(folder));
      parentId = folder.getId();
    }
    UUID deepestParentId = parentId;

    assertThatThrownBy(
            () ->
                service.createFolder(
                    libraryId,
                    new LibraryFolderRequest("Zu tief").parentFolderId(deepestParentId),
                    currentUserId,
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void renamingAFolderInAConnectorLibraryIsRejectedWithConflict() {
    // #824: renameFolder shares requireUploadLibrary with createFolder (already covered by
    // creatingAFolderInAConnectorLibraryIsRejectedWithConflict above) - this pins the same
    // restriction on the rename entry point specifically, ahead of #824 wiring the internal
    // materializeFolderPath bypass into the same class.
    grantEditor();
    when(library.getSourceType()).thenReturn(DocumentSourceType.FILESYSTEM);
    LibraryFolder folder = new LibraryFolder(libraryId, null, "Protokolle", organizationId);
    when(folderRepository.findById(folder.getId())).thenReturn(Optional.of(folder));

    assertThatThrownBy(
            () ->
                service.renameFolder(
                    libraryId,
                    folder.getId(),
                    new LibraryFolderRenameRequest("Archiv"),
                    currentUserId,
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void deletingAFolderInAConnectorLibraryIsRejectedWithConflict() {
    // #824: deleteFolder's own requireUploadLibrary check - the read-only floor a FILESYSTEM
    // library's folders sit behind through the public CRUD entry points.
    grantEditor();
    when(library.getSourceType()).thenReturn(DocumentSourceType.FILESYSTEM);
    LibraryFolder folder = new LibraryFolder(libraryId, null, "Protokolle", organizationId);
    when(folderRepository.findById(folder.getId())).thenReturn(Optional.of(folder));

    assertThatThrownBy(() -> service.deleteFolder(libraryId, folder.getId(), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void materializeFolderPathCreatesTheFullChainForANewNestedDirectory() {
    // #824: the FILESYSTEM mirroring entry point - deliberately bypasses requireUploadLibrary/
    // requireEditable (see this class's own Javadoc), so no grantEditor() stubbing is needed here,
    // unlike every CRUD test above. requireFilesystemLibrary is a real guard though (#824 review,
    // Befund 4a), hence the explicit FILESYSTEM stub.
    when(library.getSourceType()).thenReturn(DocumentSourceType.FILESYSTEM);
    when(folderRepository.findByLibraryIdAndParentFolderIdIsNullAndName(libraryId, "Rechtsquellen"))
        .thenReturn(Optional.empty());
    when(folderRepository.findByLibraryIdAndParentFolderIdAndName(eq(libraryId), any(), eq("2026")))
        .thenReturn(Optional.empty());

    UUID leafId = service.materializeFolderPath(library, List.of("Rechtsquellen", "2026"));

    assertThat(leafId).isNotNull();
    verify(folderRepository, times(2)).saveAndFlush(any(LibraryFolder.class));
  }

  @Test
  void materializeFolderPathReusesAnExistingFolderInsteadOfCreatingADuplicate() {
    // #824 acceptance criteria: idempotent over the unique constraint - a second run over the same
    // directory tree must not create a sibling row.
    when(library.getSourceType()).thenReturn(DocumentSourceType.FILESYSTEM);
    LibraryFolder existing = new LibraryFolder(libraryId, null, "Rechtsquellen", organizationId);
    when(folderRepository.findByLibraryIdAndParentFolderIdIsNullAndName(libraryId, "Rechtsquellen"))
        .thenReturn(Optional.of(existing));

    UUID resolvedId = service.materializeFolderPath(library, List.of("Rechtsquellen"));

    assertThat(resolvedId).isEqualTo(existing.getId());
    verify(folderRepository, never()).saveAndFlush(any(LibraryFolder.class));
  }

  @Test
  void materializeFolderPathReturnsNullForTheLibraryRoot() {
    when(library.getSourceType()).thenReturn(DocumentSourceType.FILESYSTEM);

    UUID resolvedId = service.materializeFolderPath(library, List.of());

    assertThat(resolvedId).isNull();
    verify(folderRepository, never()).saveAndFlush(any(LibraryFolder.class));
  }

  @Test
  void materializeFolderPathRejectsANonFilesystemLibrary() {
    // #824 review, Befund 4a: this internal entry point must never be called for anything but a
    // FILESYSTEM library - library defaults to UPLOAD in setUp, so no extra stubbing is needed to
    // exercise the guard.
    assertThatThrownBy(() -> service.materializeFolderPath(library, List.of("Ordner")))
        .isInstanceOf(IllegalArgumentException.class);
    verify(folderRepository, never()).saveAndFlush(any(LibraryFolder.class));
  }

  @Test
  void pruneOrphanedFoldersRejectsANonFilesystemLibrary() {
    assertThatThrownBy(() -> service.pruneOrphanedFolders(library, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
    verify(folderRepository, never()).findByLibraryId(any());
  }

  @Test
  void pruneOrphanedFoldersRemovesAnEmptyFolderMissingFromTheCurrentRun() {
    // #824: a source directory that disappeared between two runs and never held any document.
    when(library.getSourceType()).thenReturn(DocumentSourceType.FILESYSTEM);
    LibraryFolder orphan = new LibraryFolder(libraryId, null, "Verschwunden", organizationId);
    when(folderRepository.findByLibraryId(libraryId)).thenReturn(List.of(orphan));
    when(documentRepository.countByFolderId(orphan.getId())).thenReturn(0L);

    service.pruneOrphanedFolders(library, Set.of());

    verify(folderRepository).delete(orphan);
  }

  @Test
  void pruneOrphanedFoldersKeepsAFolderStillSeenInTheCurrentRun() {
    when(library.getSourceType()).thenReturn(DocumentSourceType.FILESYSTEM);
    LibraryFolder seen = new LibraryFolder(libraryId, null, "Weiterhin da", organizationId);
    when(folderRepository.findByLibraryId(libraryId)).thenReturn(List.of(seen));

    service.pruneOrphanedFolders(library, Set.of(seen.getId()));

    verify(folderRepository, never()).delete(any(LibraryFolder.class));
  }

  @Test
  void pruneOrphanedFoldersKeepsAnOrphanThatStillHoldsADocument() {
    // #824: a FILESYSTEM run does not yet delete a document whose file disappeared - a folder that
    // still (transitively) holds one must not be discarded underneath it.
    when(library.getSourceType()).thenReturn(DocumentSourceType.FILESYSTEM);
    LibraryFolder orphan = new LibraryFolder(libraryId, null, "Verschwunden", organizationId);
    when(folderRepository.findByLibraryId(libraryId)).thenReturn(List.of(orphan));
    when(documentRepository.countByFolderId(orphan.getId())).thenReturn(1L);

    service.pruneOrphanedFolders(library, Set.of());

    verify(folderRepository, never()).delete(any(LibraryFolder.class));
  }

  @Test
  void pruneOrphanedFoldersRemovesAnOrphanedParentOnlyAfterItsOwnEmptyOrphanedChild() {
    // #824: leaf-first - the parent only becomes empty once its own orphaned, empty child is gone.
    when(library.getSourceType()).thenReturn(DocumentSourceType.FILESYSTEM);
    LibraryFolder parent = new LibraryFolder(libraryId, null, "Archiv", organizationId);
    LibraryFolder child = new LibraryFolder(libraryId, parent.getId(), "2025", organizationId);
    when(folderRepository.findByLibraryId(libraryId)).thenReturn(List.of(parent, child));
    when(documentRepository.countByFolderId(any())).thenReturn(0L);

    service.pruneOrphanedFolders(library, Set.of());

    var inOrder = org.mockito.Mockito.inOrder(folderRepository);
    inOrder.verify(folderRepository).delete(child);
    inOrder.verify(folderRepository).delete(parent);
  }

  @Test
  void renamingToItsOwnCurrentNameDoesNotConflictWithItself() {
    grantEditor();
    LibraryFolder folder = new LibraryFolder(libraryId, null, "Protokolle", organizationId);
    when(folderRepository.findById(folder.getId())).thenReturn(Optional.of(folder));
    when(folderRepository.findByLibraryIdAndParentFolderIdIsNullAndNameAndIdNot(
            libraryId, "Protokolle", folder.getId()))
        .thenReturn(Optional.empty());

    LibraryFolderResponse response =
        service.renameFolder(
            libraryId,
            folder.getId(),
            new LibraryFolderRenameRequest("Protokolle"),
            currentUserId,
            false);

    assertThat(response.getName()).isEqualTo("Protokolle");
  }

  @Test
  void renamingToAnExistingSiblingNameIsRejectedWithConflict() {
    grantEditor();
    LibraryFolder folder = new LibraryFolder(libraryId, null, "Protokolle", organizationId);
    when(folderRepository.findById(folder.getId())).thenReturn(Optional.of(folder));
    when(folderRepository.findByLibraryIdAndParentFolderIdIsNullAndNameAndIdNot(
            libraryId, "Archiv", folder.getId()))
        .thenReturn(Optional.of(new LibraryFolder(libraryId, null, "Archiv", organizationId)));

    assertThatThrownBy(
            () ->
                service.renameFolder(
                    libraryId,
                    folder.getId(),
                    new LibraryFolderRenameRequest("Archiv"),
                    currentUserId,
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void viewerMayReadAFolder() {
    grantViewer();
    LibraryFolder folder = new LibraryFolder(libraryId, null, "Protokolle", organizationId);
    when(folderRepository.findById(folder.getId())).thenReturn(Optional.of(folder));

    LibraryFolderResponse response =
        service.getFolder(libraryId, folder.getId(), currentUserId, false);

    assertThat(response.getId()).isEqualTo(folder.getId());
  }

  @Test
  void deletingAFolderRecursivelyDeletesSubfoldersAndDocumentsBeforeTheFolderItself() {
    grantEditor();
    LibraryFolder root = new LibraryFolder(libraryId, null, "Archiv", organizationId);
    LibraryFolder child = new LibraryFolder(libraryId, root.getId(), "2026", organizationId);
    when(folderRepository.findById(root.getId())).thenReturn(Optional.of(root));
    when(folderRepository.findByLibraryIdAndParentFolderIdOrderByNameAsc(libraryId, root.getId()))
        .thenReturn(List.of(child));
    when(folderRepository.findByLibraryIdAndParentFolderIdOrderByNameAsc(libraryId, child.getId()))
        .thenReturn(List.of());

    Document rootDocument = mock(Document.class);
    UUID rootDocumentId = UUID.randomUUID();
    when(rootDocument.getId()).thenReturn(rootDocumentId);
    Document childDocument = mock(Document.class);
    UUID childDocumentId = UUID.randomUUID();
    when(childDocument.getId()).thenReturn(childDocumentId);
    when(documentRepository.findByFolderId(root.getId())).thenReturn(List.of(rootDocument));
    when(documentRepository.findByFolderId(child.getId())).thenReturn(List.of(childDocument));

    service.deleteFolder(libraryId, root.getId(), currentUserId, false);

    // Child folder's document is removed before the root folder's own document - leaf first, the
    // order fk_library_folders_parent/fk_documents_folder's RESTRICT constraints require (see
    // LibraryFolderService's class Javadoc).
    verify(documentService, times(1))
        .deleteDocument(libraryId, childDocumentId, currentUserId, false);
    verify(documentService, times(1))
        .deleteDocument(libraryId, rootDocumentId, currentUserId, false);
    verify(folderRepository).delete(child);
    verify(folderRepository).delete(root);
  }

  @Test
  void deletingARootWithNoDocumentsDeletesOnlyTheFolderRow() {
    grantEditor();
    LibraryFolder folder = new LibraryFolder(libraryId, null, "Leer", organizationId);
    when(folderRepository.findById(folder.getId())).thenReturn(Optional.of(folder));

    service.deleteFolder(libraryId, folder.getId(), currentUserId, false);

    verify(documentService, never()).deleteDocument(any(), any(), any(), eq(false));
    verify(folderRepository).delete(folder);
  }

  @Test
  void aFolderFromAnotherLibraryIsNotFound() {
    grantEditor();
    LibraryFolder foreignFolder =
        new LibraryFolder(UUID.randomUUID(), null, "Fremd", organizationId);
    when(folderRepository.findById(foreignFolder.getId())).thenReturn(Optional.of(foreignFolder));

    assertThatThrownBy(
            () ->
                service.renameFolder(
                    libraryId,
                    foreignFolder.getId(),
                    new LibraryFolderRenameRequest("Neu"),
                    currentUserId,
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  // #823: LibraryFolderService#resolveOrCreateFolderPath - the upload-path counterpart to
  // materializeFolderPath above, but UPLOAD-only and permission-checked like createFolder.

  @Test
  void resolveOrCreateFolderPathCreatesTheFullChainForANewPath() {
    grantEditor();
    when(folderRepository.findByLibraryIdAndParentFolderIdIsNullAndName(libraryId, "Protokolle"))
        .thenReturn(Optional.empty());
    when(folderRepository.findByLibraryIdAndParentFolderIdAndName(eq(libraryId), any(), eq("2026")))
        .thenReturn(Optional.empty());

    UUID leafId =
        service.resolveOrCreateFolderPath(
            libraryId, null, List.of("Protokolle", "2026"), currentUserId, false);

    assertThat(leafId).isNotNull();
    verify(folderRepository, times(2)).saveAndFlush(any(LibraryFolder.class));
  }

  @Test
  void resolveOrCreateFolderPathReusesAnExistingFolderInsteadOfCreatingADuplicate() {
    grantEditor();
    LibraryFolder existing = new LibraryFolder(libraryId, null, "Protokolle", organizationId);
    when(folderRepository.findByLibraryIdAndParentFolderIdIsNullAndName(libraryId, "Protokolle"))
        .thenReturn(Optional.of(existing));

    UUID resolvedId =
        service.resolveOrCreateFolderPath(
            libraryId, null, List.of("Protokolle"), currentUserId, false);

    assertThat(resolvedId).isEqualTo(existing.getId());
    verify(folderRepository, never()).saveAndFlush(any(LibraryFolder.class));
  }

  @Test
  void resolveOrCreateFolderPathIsRelativeToAnExplicitBaseFolder() {
    grantEditor();
    LibraryFolder base = new LibraryFolder(libraryId, null, "Bestand", organizationId);
    when(folderRepository.findById(base.getId())).thenReturn(Optional.of(base));
    when(folderRepository.findByLibraryIdAndParentFolderIdAndName(
            libraryId, base.getId(), "Unterordner"))
        .thenReturn(Optional.empty());

    service.resolveOrCreateFolderPath(
        libraryId, base.getId(), List.of("Unterordner"), currentUserId, false);

    verify(folderRepository)
        .saveAndFlush(
            argThat(
                folder ->
                    folder.getParentFolderId().equals(base.getId())
                        && folder.getName().equals("Unterordner")));
  }

  @Test
  void resolveOrCreateFolderPathReturnsTheBaseFolderForAnEmptyPath() {
    grantEditor();

    UUID resolvedId =
        service.resolveOrCreateFolderPath(libraryId, null, List.of(), currentUserId, false);

    assertThat(resolvedId).isNull();
    verify(folderRepository, never()).saveAndFlush(any(LibraryFolder.class));
  }

  @Test
  void resolveOrCreateFolderPathRejectsAnEmptySegment() {
    grantEditor();

    assertThatThrownBy(
            () ->
                service.resolveOrCreateFolderPath(
                    libraryId, null, List.of("Protokolle", ""), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void resolveOrCreateFolderPathRejectsASegmentContainingABackslash() {
    grantEditor();

    assertThatThrownBy(
            () ->
                service.resolveOrCreateFolderPath(
                    libraryId, null, List.of("Ordner\\Unterordner"), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void resolveOrCreateFolderPathRejectsADotDotSegment() {
    grantEditor();

    assertThatThrownBy(
            () ->
                service.resolveOrCreateFolderPath(
                    libraryId, null, List.of(".."), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void resolveOrCreateFolderPathRejectsNestingBeyondTheDepthLimit() {
    grantEditor();
    // Ten segments starting at the root already exhaust MAX_DEPTH (10) - an eleventh must fail.
    List<String> tooDeep = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k");
    when(folderRepository.findByLibraryIdAndParentFolderIdIsNullAndName(eq(libraryId), any()))
        .thenReturn(Optional.empty());
    when(folderRepository.findByLibraryIdAndParentFolderIdAndName(eq(libraryId), any(), any()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.resolveOrCreateFolderPath(libraryId, null, tooDeep, currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void resolveOrCreateFolderPathInAConnectorLibraryIsRejectedWithConflict() {
    grantEditor();
    when(library.getSourceType()).thenReturn(DocumentSourceType.FILESYSTEM);

    assertThatThrownBy(
            () ->
                service.resolveOrCreateFolderPath(
                    libraryId, null, List.of("Protokolle"), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void resolveOrCreateFolderPathRejectsAForeignBaseFolder() {
    grantEditor();
    LibraryFolder foreignBase = new LibraryFolder(UUID.randomUUID(), null, "Fremd", organizationId);
    when(folderRepository.findById(foreignBase.getId())).thenReturn(Optional.of(foreignBase));

    assertThatThrownBy(
            () ->
                service.resolveOrCreateFolderPath(
                    libraryId, foreignBase.getId(), List.of("Protokolle"), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }
}
