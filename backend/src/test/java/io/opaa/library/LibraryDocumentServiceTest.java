package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link LibraryDocumentService} (#420, #434): the format/size/dedup validation, the
 * path traversal guarantee, the EDITOR permission gate (and the 404-vs-403 distinction it draws
 * between no access at all and insufficient access) on both {@link
 * LibraryDocumentService#uploadDocument} and {@link LibraryDocumentService#deleteDocument}, and
 * that {@link LibraryDocumentService#deleteDocument} only ever deletes a file this service itself
 * wrote. {@link LibraryDocumentService#uploadDocument} itself now creates and saves the {@code
 * PENDING} row and only <em>triggers</em> the asynchronous indexing pipeline ({@code
 * FileProcessingService#processUploadedFileAsync}, mocked here as a no-op void call) - covered by
 * its own tests in {@code FileProcessingServiceTest} - this class is about what happens before and
 * around that call.
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
    UploadProperties uploadProperties =
        new UploadProperties(storageDir.toString(), 10L * 1024, null, 0);

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

    // #434: uploadDocument now saves the PENDING row itself (previously done inside the now-async
    // FileProcessingService#processUploadedFileAsync) - every test exercises that save unless it
    // overrides this default to simulate the concurrent-duplicate race.
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  private void grantEditor() {
    when(accessService.requireRole(any(), eq(currentUserId), eq(false), eq(AssetRole.EDITOR)))
        .thenReturn(AssetRole.EDITOR);
  }

  private void grantViewerOnly() {
    when(accessService.requireRole(any(), eq(currentUserId), eq(false), eq(AssetRole.EDITOR)))
        .thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek"));
  }

  private void grantNoAccess() {
    when(accessService.requireRole(any(), eq(currentUserId), eq(false), eq(AssetRole.EDITOR)))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Bibliothek nicht gefunden"));
  }

  // Real PDF magic bytes (#435): since uploadDocument now runs Tika content detection against the
  // actually stored bytes, a MockMultipartFile whose body is plain text would be rejected as a
  // content/extension mismatch even when it is only meant to stand in for "some PDF". Every test
  // that needs a file Tika detects as application/pdf goes through this helper.
  private static final String PDF_MAGIC_BYTES = "%PDF-1.4\n";

  private MultipartFile pdfFile(String originalFileName, String content) {
    return new MockMultipartFile(
        "file", originalFileName, "application/pdf", (PDF_MAGIC_BYTES + content).getBytes());
  }

  @Test
  void editorMayUploadADocument() throws IOException {
    grantEditor();
    when(checksumService.computeSha256(any(Path.class))).thenReturn("checksum-123");
    when(documentRepository.findByLibraryIdAndChecksum(libraryId, "checksum-123"))
        .thenReturn(Optional.empty());

    LibraryDocumentResponse response =
        service.uploadDocument(
            libraryId, pdfFile("report.pdf", "pdf content"), currentUserId, false);

    // #434: the response reflects the PENDING row created synchronously - not a result from
    // FileProcessingService, which now only runs asynchronously and returns nothing.
    assertThat(response.getFileName()).isEqualTo("report.pdf");
    assertThat(response.getSourceType()).isEqualTo(DocumentSourceType.UPLOAD);
    assertThat(response.getUploadedByUserId()).isEqualTo(currentUserId);
    assertThat(response.getStatus()).isEqualTo(DocumentStatus.PENDING);

    // The stored file lives under the library's own subdirectory of the storage path, and async
    // processing was handed exactly that path.
    ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
    verify(fileProcessingService)
        .processUploadedFileAsync(eq(response.getId()), pathCaptor.capture());
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

    verify(fileProcessingService, never()).processUploadedFileAsync(any(), any());
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
    verify(fileProcessingService, never()).processUploadedFileAsync(any(), any());
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
    verify(fileProcessingService, never()).processUploadedFileAsync(any(), any());
  }

  @Test
  void aRealPdfUploadedAsPdfIsAccepted() throws IOException {
    // #435: content detection is a positive check too, not only a rejection path - genuine PDF
    // bytes with a .pdf extension must not be caught by the new guard.
    grantEditor();
    when(checksumService.computeSha256(any(Path.class))).thenReturn("checksum-real-pdf");
    when(documentRepository.findByLibraryIdAndChecksum(libraryId, "checksum-real-pdf"))
        .thenReturn(Optional.empty());

    LibraryDocumentResponse response =
        service.uploadDocument(
            libraryId, pdfFile("report.pdf", "%PDF content"), currentUserId, false);

    assertThat(response.getFileName()).isEqualTo("report.pdf");
    verify(fileProcessingService).processUploadedFileAsync(eq(response.getId()), any());
  }

  @Test
  void aRealDocxUploadedAsDocxIsAccepted() throws IOException {
    // #435 code review, finding 1: the riskiest cases in STRICT_CONTENT_TYPES_BY_EXTENSION are the
    // Office formats, because their correct detection depends on transitive Tika parser modules
    // (tika-parsers-standard, POI) actually being on the classpath - a plain byte literal like
    // pdfFile's "%PDF-1.4\n" cannot stand in for them the way it can for PDF. Building a genuine
    // .docx with POI (already on the test classpath via spring-ai-tika-document-reader) exercises
    // the real ZipContainerDetector/POIFSContainerDetector path end to end, so a future Spring AI
    // bump that trims those parsers would turn this test red instead of failing silently in
    // production.
    grantEditor();
    when(checksumService.computeSha256(any(Path.class))).thenReturn("checksum-docx");
    when(documentRepository.findByLibraryIdAndChecksum(libraryId, "checksum-docx"))
        .thenReturn(Optional.empty());

    LibraryDocumentResponse response =
        service.uploadDocument(libraryId, realDocxFile("vertrag.docx"), currentUserId, false);

    assertThat(response.getFileName()).isEqualTo("vertrag.docx");
    verify(fileProcessingService).processUploadedFileAsync(eq(response.getId()), any());
  }

  private MultipartFile realDocxFile(String originalFileName) throws IOException {
    try (XWPFDocument document = new XWPFDocument()) {
      XWPFParagraph paragraph = document.createParagraph();
      XWPFRun run = paragraph.createRun();
      run.setText("Ein echter DOCX-Inhalt fuer den Formaterkennungstest.");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.write(out);
      return new MockMultipartFile(
          "file",
          originalFileName,
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
          out.toByteArray());
    }
  }

  @Test
  void binaryGarbageClaimingToBeAPdfIsRejectedWithABadRequestAndAGermanMessage()
      throws IOException {
    // #435, Maintainer-Entscheidung 20.08.2026: Tika's magic-byte detection catches the mismatch
    // between a claimed .pdf extension and content that is not actually a PDF.
    grantEditor();

    MultipartFile fakePdf =
        new MockMultipartFile(
            "file",
            "not-really-a-pdf.pdf",
            "application/pdf",
            new byte[] {0x00, 0x01, 0x02, (byte) 0xFF, 0x00, (byte) 0xDE, (byte) 0xAD});

    assertThatThrownBy(() -> service.uploadDocument(libraryId, fakePdf, currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST)
        .hasMessageContaining("entspricht nicht dem Format .pdf");

    assertNoFilesWereStored();
    verify(fileProcessingService, never()).processUploadedFileAsync(any(), any());
  }

  @Test
  void markdownTextUploadedAsMdIsAcceptedWithoutRequiringAnExactMediaType() throws IOException {
    // #435, "Toleranz bei Textformaten": .md content only has to look like text, not match one
    // specific detected media type - Markdown syntax itself is not a distinct magic-byte format.
    grantEditor();
    when(checksumService.computeSha256(any(Path.class))).thenReturn("checksum-md");
    when(documentRepository.findByLibraryIdAndChecksum(libraryId, "checksum-md"))
        .thenReturn(Optional.empty());

    MultipartFile markdown =
        new MockMultipartFile(
            "file", "notes.md", "text/markdown", "# Titel\n\nEin Absatz Text.".getBytes());

    LibraryDocumentResponse response =
        service.uploadDocument(libraryId, markdown, currentUserId, false);

    assertThat(response.getFileName()).isEqualTo("notes.md");
    verify(fileProcessingService).processUploadedFileAsync(eq(response.getId()), any());
  }

  @Test
  void pdfContentUploadedAsTxtIsRejectedAsBinaryContentClaimingToBeText() throws IOException {
    // #435: the text-tolerant check for .txt/.md still rejects genuinely binary content - it only
    // waives the requirement for one *specific* text media type, not the text-vs-binary distinction
    // itself.
    grantEditor();

    MultipartFile pdfAsTxt =
        new MockMultipartFile(
            "file", "report.txt", "text/plain", (PDF_MAGIC_BYTES + "binary-ish").getBytes());

    assertThatThrownBy(() -> service.uploadDocument(libraryId, pdfAsTxt, currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST)
        .hasMessageContaining("entspricht nicht dem Format .txt");

    assertNoFilesWereStored();
    verify(fileProcessingService, never()).processUploadedFileAsync(any(), any());
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
    verify(fileProcessingService, never()).processUploadedFileAsync(any(), any());
  }

  @Test
  void aFailedRowWithTheSameChecksumIsReplacedInsteadOfBlockingARetry() throws IOException {
    // PR #589 review, item 3: a FAILED row (e.g. from a transient embedding failure) must not
    // block the same file being uploaded again forever - the dedup check now only rejects a
    // still-live (PENDING/INDEXED) match, and replaces a FAILED one.
    grantEditor();
    Path libraryDir = Files.createDirectories(storageDir.resolve(libraryId.toString()));
    Path oldFailedFile = libraryDir.resolve("old-failed.pdf");
    Files.writeString(oldFailedFile, "content from the failed attempt");

    Document oldFailedDoc =
        new Document(
            "report.pdf",
            oldFailedFile.toString(),
            "application/pdf",
            5L,
            DocumentSourceType.UPLOAD);
    oldFailedDoc.setLibraryId(libraryId);
    oldFailedDoc.setStatus(DocumentStatus.FAILED);
    oldFailedDoc.setErrorMessage("Die Datei konnte nicht verarbeitet werden");

    when(checksumService.computeSha256(any(Path.class))).thenReturn("checksum-retry");
    when(documentRepository.findByLibraryIdAndChecksum(libraryId, "checksum-retry"))
        .thenReturn(Optional.of(oldFailedDoc));

    LibraryDocumentResponse response =
        service.uploadDocument(
            libraryId, pdfFile("report.pdf", "pdf content"), currentUserId, false);

    assertThat(response.getStatus()).isEqualTo(DocumentStatus.PENDING);
    verify(vectorStore).delete("document_id == '" + oldFailedDoc.getId() + "'");
    verify(documentRepository).delete(oldFailedDoc);
    assertThat(Files.exists(oldFailedFile))
        .as("The old FAILED row's own file must be cleaned up when it is replaced")
        .isFalse();
    verify(fileProcessingService).processUploadedFileAsync(eq(response.getId()), any());
  }

  @Test
  void aRaceThatSlipsPastTheChecksumCheckIsStillCaughtByTheUniqueIndex() throws IOException {
    // #420 code review, nit 5: the sequential findByLibraryIdAndChecksum check cannot close a
    // race between two concurrent uploads; uk_documents_library_checksum (migration 020) does, and
    // this is the resulting DataIntegrityViolationException translated into the same 409. #434: the
    // save that can now raise it is the PENDING row's own save, done inside this service - not a
    // call into FileProcessingService any more.
    grantEditor();
    when(checksumService.computeSha256(any(Path.class))).thenReturn("checksum-race");
    when(documentRepository.findByLibraryIdAndChecksum(libraryId, "checksum-race"))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class)))
        .thenThrow(new DataIntegrityViolationException("uk_documents_library_checksum"));

    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    libraryId, pdfFile("racer.pdf", "same content"), currentUserId, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);

    assertNoFilesWereStored();
    verify(fileProcessingService, never()).processUploadedFileAsync(any(), any());
  }

  @Test
  void aPathTraversingFileNameNeverEscapesTheLibraryStorageDirectory() throws IOException {
    grantEditor();
    when(checksumService.computeSha256(any(Path.class))).thenReturn("checksum-xyz");
    when(documentRepository.findByLibraryIdAndChecksum(libraryId, "checksum-xyz"))
        .thenReturn(Optional.empty());

    LibraryDocumentResponse response =
        service.uploadDocument(
            libraryId,
            new MockMultipartFile(
                "file",
                "../../../../etc/evil.pdf",
                "application/pdf",
                (PDF_MAGIC_BYTES + "content").getBytes()),
            currentUserId,
            false);

    assertThat(response.getFileName()).isEqualTo("evil.pdf");
    ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
    verify(fileProcessingService)
        .processUploadedFileAsync(eq(response.getId()), pathCaptor.capture());
    Path storedPath = pathCaptor.getValue().toAbsolutePath().normalize();
    Path libraryDir = storageDir.resolve(libraryId.toString()).toAbsolutePath().normalize();
    assertThat(storedPath.startsWith(libraryDir))
        .as("Stored file must stay inside the library's own storage directory")
        .isTrue();
  }

  @Test
  void aFullProcessingQueueMarksTheDocumentFailedInsteadOfLeavingItPendingForever()
      throws IOException {
    // PR #589 review, item 2: uploadTaskExecutor rejects (AbortPolicy) rather than silently
    // discarding a task when its queue is full - this is where LibraryDocumentService catches
    // that rejection and turns it into an immediate, explained FAILED row instead of a PENDING
    // one nothing will ever pick up.
    grantEditor();
    when(checksumService.computeSha256(any(Path.class))).thenReturn("checksum-queue-full");
    when(documentRepository.findByLibraryIdAndChecksum(libraryId, "checksum-queue-full"))
        .thenReturn(Optional.empty());
    doThrow(new TaskRejectedException("queue is full"))
        .when(fileProcessingService)
        .processUploadedFileAsync(any(), any());

    LibraryDocumentResponse response =
        service.uploadDocument(
            libraryId, pdfFile("report.pdf", "pdf content"), currentUserId, false);

    assertThat(response.getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(response.getErrorMessage())
        .isEqualTo("Die Verarbeitung ist derzeit ausgelastet - bitte später erneut versuchen.");
    assertNoFilesWereStored();
  }

  @Test
  void anUnexpectedFailureStartingProcessingMarksTheAlreadyPersistedRowFailed() throws IOException {
    // PR #589 review, item 4: once the PENDING row is committed, a RuntimeException must never
    // again just delete the file and rethrow, leaving the row behind pointing at a dead
    // file_path - it is marked FAILED instead, the same way the more specific
    // TaskRejectedException case above already is.
    grantEditor();
    when(checksumService.computeSha256(any(Path.class))).thenReturn("checksum-unexpected");
    when(documentRepository.findByLibraryIdAndChecksum(libraryId, "checksum-unexpected"))
        .thenReturn(Optional.empty());
    doThrow(new IllegalStateException("submission blew up unexpectedly"))
        .when(fileProcessingService)
        .processUploadedFileAsync(any(), any());

    LibraryDocumentResponse response =
        service.uploadDocument(
            libraryId, pdfFile("report.pdf", "pdf content"), currentUserId, false);

    assertThat(response.getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(response.getErrorMessage())
        .isEqualTo("Die Verarbeitung konnte nicht gestartet werden");
    assertNoFilesWereStored();
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
  void deletingAnUploadedDocumentDeletesTheRowBeforeTheVectorStoreChunks() throws IOException {
    // #614, PR #589 second review round, finding 2: a concurrent uploadTaskExecutor task finishing
    // the very same document races this method. DocumentRepository#markIndexed/#markFailed are
    // conditional UPDATEs that only ever affect a row that still exists - so the row must already
    // be gone (or at least already deleted within this transaction) by the time the vector store
    // delete below runs, or a racing task's own chunk writes could survive after this method's
    // vectorStore.delete already ran and will never run again. Verifying call order is the
    // unit-test
    // approximation of that guarantee; the genuine cross-thread race is exercised end to end by
    // LibraryDocumentServiceIntegrationTest.
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

    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(documentRepository, vectorStore);
    inOrder.verify(documentRepository).delete(doc);
    inOrder.verify(vectorStore).delete("document_id == '" + doc.getId() + "'");
  }

  @Test
  void aFailingVectorStoreDeleteDuringCleanupStillLetsTheFileBeDeletedAndDoesNotPropagate()
      throws IOException {
    // PR #631 review, finding 1: by the time this afterCommit callback runs, the row deletion has
    // already committed - the caller's request has already succeeded. A vectorStore.delete failure
    // here must neither turn that success into a 500 the caller never asked for, nor skip the file
    // deletion for a reason that has nothing to do with the file.
    grantEditor();
    UUID documentId = UUID.randomUUID();
    Path libraryDir = Files.createDirectories(storageDir.resolve(libraryId.toString()));
    Path storedFile = libraryDir.resolve("stored.pdf");
    Files.writeString(storedFile, "content");

    Document doc = new Document("report.pdf", storedFile.toString(), "application/pdf", 7L);
    doc.setLibraryId(libraryId);
    doc.setSourceType(DocumentSourceType.UPLOAD);
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(doc));
    doThrow(new RuntimeException("pgvector unavailable"))
        .when(vectorStore)
        .delete("document_id == '" + doc.getId() + "'");

    service.deleteDocument(libraryId, documentId, currentUserId, false);

    verify(documentRepository).delete(doc);
    assertThat(Files.exists(storedFile))
        .as("The file must still be deleted even though the vector store cleanup failed")
        .isFalse();
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
