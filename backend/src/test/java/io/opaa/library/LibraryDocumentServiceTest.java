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
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link LibraryDocumentService} (#420): the format/size/dedup validation, the path
 * traversal guarantee, and the EDITOR permission gate on both {@link
 * LibraryDocumentService#uploadDocument} and {@link LibraryDocumentService#deleteDocument}. The
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
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
  }

  private MultipartFile pdfFile(String originalFileName, String content) {
    return new MockMultipartFile("file", originalFileName, "application/pdf", content.getBytes());
  }

  @Test
  void editorMayUploadADocument() throws IOException {
    when(accessService.canEdit(any(), eq(currentUserId), eq(false))).thenReturn(true);
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
    when(accessService.canEdit(any(), eq(currentUserId), eq(false))).thenReturn(false);

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
  void anUnsupportedFormatIsRejectedAndNoFileIsStored() throws IOException {
    when(accessService.canEdit(any(), eq(currentUserId), eq(false))).thenReturn(true);

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
    when(accessService.canEdit(any(), eq(currentUserId), eq(false))).thenReturn(true);
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
    when(accessService.canEdit(any(), eq(currentUserId), eq(false))).thenReturn(true);
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
  void aPathTraversingFileNameNeverEscapesTheLibraryStorageDirectory() throws IOException {
    when(accessService.canEdit(any(), eq(currentUserId), eq(false))).thenReturn(true);
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
    when(accessService.canEdit(any(), eq(currentUserId), eq(false))).thenReturn(false);

    assertThatThrownBy(
            () -> service.deleteDocument(libraryId, UUID.randomUUID(), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
    verify(documentRepository, never()).delete(any());
  }

  @Test
  void deletingAMissingDocumentIs404() {
    when(accessService.canEdit(any(), eq(currentUserId), eq(false))).thenReturn(true);
    UUID documentId = UUID.randomUUID();
    when(documentRepository.findById(documentId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.deleteDocument(libraryId, documentId, currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
  }

  @Test
  void deletingADocumentThatBelongsToAnotherLibraryIs404() {
    when(accessService.canEdit(any(), eq(currentUserId), eq(false))).thenReturn(true);
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
  void deletingADocumentRemovesChunksTheRowAndTheStoredFile() throws IOException {
    when(accessService.canEdit(any(), eq(currentUserId), eq(false))).thenReturn(true);
    UUID documentId = UUID.randomUUID();
    Path storedFile = storageDir.resolve("stored.pdf");
    Files.writeString(storedFile, "content");

    Document doc = new Document("report.pdf", storedFile.toString(), "application/pdf", 7L);
    doc.setLibraryId(libraryId);
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(doc));

    service.deleteDocument(libraryId, documentId, currentUserId, false);

    verify(vectorStore).delete("document_id == '" + doc.getId() + "'");
    verify(documentRepository).delete(doc);
    assertThat(Files.exists(storedFile)).isFalse();
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
