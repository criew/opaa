package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.dto.LibraryDocumentResponse;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.indexing.ChecksumService;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.DocumentSourceType;
import io.opaa.indexing.DocumentStatus;
import io.opaa.indexing.EmptyDocumentContentException;
import io.opaa.indexing.FileProcessingService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link LibraryDocumentService} (#420): the format/size/dedup validation, the path
 * traversal guarantee, the EDITOR permission gate (and the 404-vs-403 distinction it draws between
 * no access at all and insufficient access) on both {@link LibraryDocumentService#uploadDocument}
 * and {@link LibraryDocumentService#deleteDocument}, and that {@link
 * LibraryDocumentService#deleteDocument} only ever deletes a file this service itself wrote. The
 * indexing pipeline itself ({@code FileProcessingService#processUploadedFile}) is mocked here and
 * covered by its own tests in {@code FileProcessingServiceTest} - this class is about what happens
 * before and around that call.
 */
class LibraryDocumentServiceTest {

  private KnowledgeLibraryRepository libraryRepository;
  private UserRepository userRepository;
  private LibraryAccessService accessService;
  private DocumentRepository documentRepository;
  private ChecksumService checksumService;
  private FileProcessingService fileProcessingService;
  private VectorStore vectorStore;
  private LibraryDocumentService service;

  private final UUID currentUserId = UUID.randomUUID();
  private final UUID organizationId = UUID.randomUUID();
  private final UUID libraryId = UUID.randomUUID();

  @TempDir Path storageDir;

  @BeforeEach
  void setUp() {
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    userRepository = mock(UserRepository.class);
    accessService = mock(LibraryAccessService.class);
    documentRepository = mock(DocumentRepository.class);
    checksumService = mock(ChecksumService.class);
    fileProcessingService = mock(FileProcessingService.class);
    vectorStore = mock(VectorStore.class);
    UploadProperties uploadProperties = new UploadProperties(storageDir.toString(), 10L * 1024);

    service =
        new LibraryDocumentService(
            libraryRepository,
            userRepository,
            accessService,
            documentRepository,
            checksumService,
            fileProcessingService,
            vectorStore,
            uploadProperties);

    User user = new User("subject", "issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationId);
    when(userRepository.findById(currentUserId)).thenReturn(Optional.of(user));

    KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    when(library.getId()).thenReturn(libraryId);
    when(library.getOrganizationId()).thenReturn(organizationId);
    // Every existing test here exercises an UPLOAD library - #479's requireUploadLibrary check
    // (see uploadingIntoAConnectorLibraryIsRejectedWithConflict below for the connector case)
    // would otherwise reject every upload with 409 before reaching the behaviour under test,
    // since Mockito's default for an unstubbed getSourceType() is null, not UPLOAD.
    when(library.getSourceType()).thenReturn(DocumentSourceType.UPLOAD);
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
  }

  private void grantEditor() {
    when(accessService.effectiveRole(any(), eq(currentUserId), eq(false)))
        .thenReturn(AssetRole.EDITOR);
  }

  private void grantViewerOnly() {
    when(accessService.effectiveRole(any(), eq(currentUserId), eq(false)))
        .thenReturn(AssetRole.VIEWER);
  }

  private void grantNoAccess() {
    when(accessService.effectiveRole(any(), eq(currentUserId), eq(false))).thenReturn(null);
  }

  private MultipartFile pdfFile(String originalFileName, String content) {
    return new MockMultipartFile("file", originalFileName, "application/pdf", content.getBytes());
  }

  @Test
  void editorMayUploadADocument() throws IOException {
    grantEditor();
    when(checksumService.computeSha256(any(Path.class))).thenReturn("checksum-123");
    when(documentRepository.findByLibraryIdAndChecksum(libraryId, "checksum-123"))
        .thenReturn(Optional.empty());

    Document processed = new Document("report.pdf", "irrelevant", "application/pdf", 10L);
    processed.setSourceType(DocumentSourceType.UPLOAD);
    processed.setStatus(DocumentStatus.INDEXED);
    processed.setUploadedByUserId(currentUserId);
    when(fileProcessingService.processUploadedFile(
            any(Path.class),
            eq("report.pdf"),
            eq("checksum-123"),
            eq(libraryId),
            eq(organizationId),
            eq(currentUserId)))
        .thenReturn(processed);

    LibraryDocumentResponse response =
        service.uploadDocument(
            libraryId, pdfFile("report.pdf", "pdf content"), currentUserId, false);

    assertThat(response.getFileName()).isEqualTo("report.pdf");
    assertThat(response.getSourceType()).isEqualTo(DocumentSourceType.UPLOAD);
    assertThat(response.getUploadedByUserId()).isEqualTo(currentUserId);

    // The stored file lives under the library's own subdirectory of the storage path.
    ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
    verify(fileProcessingService)
        .processUploadedFile(pathCaptor.capture(), anyString(), anyString(), any(), any(), any());
    assertThat(pathCaptor.getValue()).isNotNull();
    assertThat(pathCaptor.getValue().startsWith(storageDir.resolve(libraryId.toString()))).isTrue();
    assertThat(pathCaptor.getValue().getFileName().toString()).endsWith(".pdf");
  }

  @Test
  void aViewerCannotUpload() throws IOException {
    grantViewerOnly();

    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    libraryId, pdfFile("report.pdf", "pdf content"), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);

    verify(fileProcessingService, never())
        .processUploadedFile(any(), anyString(), anyString(), any(), any(), any());
  }

  @Test
  void aUserWithNoAccessAtAllGets404NotForbidden() throws IOException {
    // #420 code review, nit 9: "no access at all" must look like the library does not exist, not
    // like a library that exists but refuses this caller - the same distinction 404-vs-403 already
    // draws across the organization boundary.
    grantNoAccess();

    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    libraryId, pdfFile("report.pdf", "pdf content"), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
  }

  @Test
  void uploadingIntoALibraryFromAnotherOrganizationLooksLikeItDoesNotExist() {
    UUID otherOrganizationId = UUID.randomUUID();
    KnowledgeLibrary foreignLibrary = mock(KnowledgeLibrary.class);
    when(foreignLibrary.getOrganizationId()).thenReturn(otherOrganizationId);
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(foreignLibrary));

    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    libraryId, pdfFile("report.pdf", "pdf content"), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
  }

  @Test
  void uploadingIntoAConnectorLibraryIsRejectedWithConflict() throws IOException {
    // #479, ADR-0018 Entscheidung 1: only a UPLOAD library accepts manually uploaded files - a
    // connector library rejects the attempt outright, before any format/size/dedup validation.
    grantEditor();
    KnowledgeLibrary connectorLibrary = mock(KnowledgeLibrary.class);
    when(connectorLibrary.getId()).thenReturn(libraryId);
    when(connectorLibrary.getOrganizationId()).thenReturn(organizationId);
    when(connectorLibrary.getSourceType()).thenReturn(DocumentSourceType.FILESYSTEM);
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(connectorLibrary));

    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    libraryId, pdfFile("report.pdf", "pdf content"), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);

    assertNoFilesWereStored();
    verify(fileProcessingService, never())
        .processUploadedFile(any(), anyString(), anyString(), any(), any(), any());
  }

  @Test
  void anUnsupportedFormatIsRejectedAndNoFileIsStored() throws IOException {
    grantEditor();

    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    libraryId,
                    new MockMultipartFile(
                        "file", "malware.exe", "application/octet-stream", "x".getBytes()),
                    currentUserId,
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);

    assertNoFilesWereStored();
    verify(fileProcessingService, never())
        .processUploadedFile(any(), anyString(), anyString(), any(), any(), any());
  }

  @Test
  void aFileOverTheSizeLimitIsRejectedWithoutBeingStored() throws IOException {
    grantEditor();
    String tooBig = "x".repeat(11 * 1024);

    assertThatThrownBy(
            () ->
                service.uploadDocument(libraryId, pdfFile("big.pdf", tooBig), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.PAYLOAD_TOO_LARGE);

    assertNoFilesWereStored();
  }

  @Test
  void aChecksumAlreadyPresentInTheSameLibraryIsRejectedAndTheFileIsRemovedAgain()
      throws IOException {
    grantEditor();
    when(checksumService.computeSha256(any(Path.class))).thenReturn("duplicate-checksum");
    when(documentRepository.findByLibraryIdAndChecksum(libraryId, "duplicate-checksum"))
        .thenReturn(Optional.of(new Document("existing.pdf", "path", "application/pdf", 5L)));

    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    libraryId, pdfFile("copy.pdf", "same content"), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);

    assertNoFilesWereStored();
    verify(fileProcessingService, never())
        .processUploadedFile(any(), anyString(), anyString(), any(), any(), any());
  }

  @Test
  void aRaceThatSlipsPastTheChecksumCheckIsStillCaughtByTheUniqueIndex() throws IOException {
    // #420 code review, nit 5: the sequential findByLibraryIdAndChecksum check cannot close a
    // race between two concurrent uploads; uk_documents_library_checksum (migration 020) does, and
    // this is the resulting DataIntegrityViolationException translated into the same 409.
    grantEditor();
    when(checksumService.computeSha256(any(Path.class))).thenReturn("checksum-race");
    when(documentRepository.findByLibraryIdAndChecksum(libraryId, "checksum-race"))
        .thenReturn(Optional.empty());
    when(fileProcessingService.processUploadedFile(
            any(), anyString(), eq("checksum-race"), any(), any(), any()))
        .thenThrow(new DataIntegrityViolationException("uk_documents_library_checksum"));

    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    libraryId, pdfFile("racer.pdf", "same content"), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);

    assertNoFilesWereStored();
  }

  @Test
  void aFileWithNoExtractableContentIsRejectedAsUnprocessable() throws IOException {
    grantEditor();
    when(checksumService.computeSha256(any(Path.class))).thenReturn("checksum-empty");
    when(documentRepository.findByLibraryIdAndChecksum(libraryId, "checksum-empty"))
        .thenReturn(Optional.empty());
    when(fileProcessingService.processUploadedFile(
            any(), anyString(), eq("checksum-empty"), any(), any(), any()))
        .thenThrow(new EmptyDocumentContentException("blank.pdf"));

    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    libraryId, pdfFile("blank.pdf", "no extractable text"), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNPROCESSABLE_ENTITY);

    // The stored file is cleaned up just like any other post-storage failure - no orphaned file
    // survives a rejected upload.
    assertNoFilesWereStored();
  }

  @Test
  void aPathTraversingFileNameNeverEscapesTheLibraryStorageDirectory() throws IOException {
    grantEditor();
    when(checksumService.computeSha256(any(Path.class))).thenReturn("checksum-xyz");
    when(documentRepository.findByLibraryIdAndChecksum(libraryId, "checksum-xyz"))
        .thenReturn(Optional.empty());

    Document processed = new Document("evil.pdf", "irrelevant", "application/pdf", 5L);
    processed.setSourceType(DocumentSourceType.UPLOAD);
    when(fileProcessingService.processUploadedFile(
            any(Path.class), eq("evil.pdf"), anyString(), any(), any(), any()))
        .thenReturn(processed);

    MultipartFile traversal =
        new MockMultipartFile(
            "file", "../../../../etc/evil.pdf", "application/pdf", "content".getBytes());

    service.uploadDocument(libraryId, traversal, currentUserId, false);

    ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
    verify(fileProcessingService)
        .processUploadedFile(
            pathCaptor.capture(), eq("evil.pdf"), anyString(), any(), any(), any());
    Path storedPath = pathCaptor.getValue().toAbsolutePath().normalize();
    Path libraryDir = storageDir.resolve(libraryId.toString()).toAbsolutePath().normalize();
    assertThat(storedPath.startsWith(libraryDir))
        .as("Stored file must stay inside the library's own storage directory")
        .isTrue();
  }

  @Test
  void aViewerCannotDelete() {
    grantViewerOnly();

    assertThatThrownBy(
            () -> service.deleteDocument(libraryId, UUID.randomUUID(), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
    verify(documentRepository, never()).delete(any());
  }

  @Test
  void aUserWithNoAccessAtAllCannotEvenTellTheLibraryExists() {
    grantNoAccess();

    assertThatThrownBy(
            () -> service.deleteDocument(libraryId, UUID.randomUUID(), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
    verify(documentRepository, never()).delete(any());
  }

  @Test
  void deletingAMissingDocumentIs404() {
    grantEditor();
    UUID documentId = UUID.randomUUID();
    when(documentRepository.findById(documentId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.deleteDocument(libraryId, documentId, currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
  }

  @Test
  void deletingADocumentThatBelongsToAnotherLibraryIs404() {
    grantEditor();
    UUID documentId = UUID.randomUUID();
    Document foreignDoc = new Document("other.pdf", "path", "application/pdf", 5L);
    foreignDoc.setLibraryId(UUID.randomUUID());
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(foreignDoc));

    assertThatThrownBy(() -> service.deleteDocument(libraryId, documentId, currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
    verify(documentRepository, never()).delete(any());
  }

  @Test
  void deletingAnUploadedDocumentRemovesChunksTheRowAndTheStoredFile() throws IOException {
    grantEditor();
    UUID documentId = UUID.randomUUID();
    Path libraryDir = Files.createDirectories(storageDir.resolve(libraryId.toString()));
    Path storedFile = libraryDir.resolve("stored.pdf");
    Files.writeString(storedFile, "content");

    Document doc = new Document("report.pdf", storedFile.toString(), "application/pdf", 7L);
    doc.setLibraryId(libraryId);
    doc.setSourceType(DocumentSourceType.UPLOAD);
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(doc));

    service.deleteDocument(libraryId, documentId, currentUserId, false);

    verify(vectorStore).delete("document_id == '" + doc.getId() + "'");
    verify(documentRepository).delete(doc);
    assertThat(Files.exists(storedFile)).isFalse();
  }

  @Test
  void deletingAFilesystemSourcedDocumentNeverTouchesItsFile() throws IOException {
    // #420 code review, finding 1 (blocking): a FILESYSTEM document's file_path points at the
    // operator-managed indexing directory, not at anything this service is allowed to remove -
    // even if that path happens to live outside the upload storage tree, and even though the row
    // and its chunks are still removed like any other document.
    grantEditor();
    UUID documentId = UUID.randomUUID();
    Path externalDir = Files.createDirectory(storageDir.resolve("operator-managed-crawl-source"));
    Path externalFile = externalDir.resolve("dienstanweisung.txt");
    Files.writeString(externalFile, "crawled content, not ours to delete");

    Document doc =
        new Document(
            "dienstanweisung.txt",
            externalFile.toString(),
            "text/plain",
            30L,
            DocumentSourceType.FILESYSTEM);
    doc.setLibraryId(libraryId);
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(doc));

    service.deleteDocument(libraryId, documentId, currentUserId, false);

    verify(vectorStore).delete("document_id == '" + doc.getId() + "'");
    verify(documentRepository).delete(doc);
    assertThat(Files.exists(externalFile))
        .as("A FILESYSTEM document's source file must survive deleteDocument")
        .isTrue();
  }

  @Test
  void deletingAnUploadDocumentWhoseFilePathWasTamperedWithOutsideItsLibraryDirIsNotDeleted()
      throws IOException {
    // Defence in depth: sourceType == UPLOAD alone is not proof that file_path is trustworthy: it
    // must also actually resolve under this library's own upload subdirectory.
    grantEditor();
    UUID documentId = UUID.randomUUID();
    Path outsideFile = storageDir.resolve("not-in-any-library-dir.pdf");
    Files.writeString(outsideFile, "content");

    Document doc =
        new Document(
            "tampered.pdf",
            outsideFile.toString(),
            "application/pdf",
            5L,
            DocumentSourceType.UPLOAD);
    doc.setLibraryId(libraryId);
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(doc));

    service.deleteDocument(libraryId, documentId, currentUserId, false);

    assertThat(Files.exists(outsideFile)).isTrue();
  }

  private void assertNoFilesWereStored() throws IOException {
    if (!Files.exists(storageDir)) {
      return;
    }
    try (var walk = Files.walk(storageDir)) {
      assertThat(walk.filter(Files::isRegularFile)).isEmpty();
    }
  }
}
