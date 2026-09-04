package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.PayloadTooLargeException;
import io.opaa.common.ServiceUnavailableException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.AttachmentExtractor;
import io.opaa.indexing.ChecksumService;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.FullTextChunkStore;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.VectorStoreWriter;
import io.opaa.indexing.pipeline.mail.MailProperties;
import io.opaa.indexing.source.filesystem.FilesystemPathAllowlist;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

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
  private LibraryAccessService accessService;
  private DocumentRepository documentRepository;
  private ChecksumService checksumService;
  private FileProcessingService fileProcessingService;
  private VectorStore vectorStore;
  private VectorChunkStore vectorChunkStore;
  private LibraryStorageQuotaService storageQuotaService;
  private FilesystemPathAllowlist filesystemAllowlist;
  // Target validation disabled here (#747): every server this class's remote-content tests talk to
  // is deliberately loopback, and TargetAddressValidator's own SSRF logic is already covered by its
  // dedicated TargetAddressValidatorTest/BoundedDownloaderTest - mirrors those suites' identical
  // choice for the same reason. loadContentAnswers404WhenTheAllowlistRejectsTheStoredSourceUrl
  // below builds its own, deliberately enabled validator instead.
  private BoundedDownloader boundedDownloader;
  private TargetAddressValidator disabledTargetAddressValidator;
  private RemoteContentProperties remoteContentProperties;
  private LibraryFolderRepository folderRepository;
  // #823: mocked no-op here - folderPath resolution is covered by its own tests further down and
  // by LibraryFolderServiceTest/LibraryFolderServiceIntegrationTest, not by every pre-existing test
  // in this class that never passes a folderPath at all.
  private LibraryFolderService folderService;
  private AttachmentExtractor attachmentExtractor;
  private UploadProperties uploadProperties;
  private LibraryDocumentService service;

  private final UUID currentUserId = UUID.randomUUID();
  private final UUID organizationId = UUID.randomUUID();
  private final UUID libraryId = UUID.randomUUID();
  private final CurrentUser caller =
      CurrentUser.of(currentUserId, organizationId, SystemRole.USER, "Test User");

  @TempDir Path storageDir;

  @BeforeEach
  void setUp() {
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    accessService = mock(LibraryAccessService.class);
    documentRepository = mock(DocumentRepository.class);
    checksumService = mock(ChecksumService.class);
    fileProcessingService = mock(FileProcessingService.class);
    vectorStore = mock(VectorStore.class);
    vectorChunkStore =
        new VectorChunkStore(
            vectorStore,
            mock(org.springframework.ai.embedding.EmbeddingModel.class),
            mock(org.springframework.ai.embedding.BatchingStrategy.class),
            mock(VectorStoreWriter.class),
            mock(FullTextChunkStore.class));
    uploadProperties = new UploadProperties(storageDir.toString(), 10L * 1024, null, 0, 0);
    storageQuotaService = mock(LibraryStorageQuotaService.class);
    // Default: plenty of headroom, so existing tests exercising other behaviour never trip the
    // quota check unless they explicitly stub it otherwise (see
    // uploadIsRejectedWithPayloadTooLargeWhenTheLibraryQuotaWouldBeExceeded below).
    when(storageQuotaService.wouldExceedQuota(any(), anyLong())).thenReturn(false);
    filesystemAllowlist = mock(FilesystemPathAllowlist.class);
    // Default: every FILESYSTEM sourcePath used below is treated as allowed unless a test
    // explicitly narrows this - see the allowlist-specific tests further down, which override it.
    when(filesystemAllowlist.isAllowed(any())).thenReturn(true);
    disabledTargetAddressValidator = TargetAddressValidator.disabled();
    boundedDownloader = new BoundedDownloader(disabledTargetAddressValidator);
    remoteContentProperties = new RemoteContentProperties(10L * 1024 * 1024, 5);
    folderRepository = mock(LibraryFolderRepository.class);
    folderService = mock(LibraryFolderService.class);
    attachmentExtractor = mock(AttachmentExtractor.class);

    service = serviceWith(new AttachmentExtractionProperties(0, null));

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
        .thenThrow(new AccessDeniedException("Kein Zugriff auf diese Bibliothek"));
  }

  private void grantNoAccess() {
    when(accessService.requireRole(any(), eq(currentUserId), eq(false), eq(AssetRole.EDITOR)))
        .thenThrow(new NotFoundException("Bibliothek nicht gefunden"));
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

  // Mirrors VectorChunkStore#deleteByDocumentId's own filter construction, so assertions here
  // compare against the actual Filter.Expression the helper builds rather than the pre-#838 raw
  // delete string.
  private static Filter.Expression documentIdFilter(UUID documentId) {
    return new FilterExpressionBuilder().eq("document_id", documentId.toString()).build();
  }

  /**
   * A service sharing this class' mocks but its own {@link AttachmentExtractionLimiter} - the #1243
   * concurrency tests need a tighter ceiling than the default one {@link #setUp} wires up.
   */
  private LibraryDocumentService serviceWith(AttachmentExtractionProperties limits) {
    return new LibraryDocumentService(
        libraryRepository,
        accessService,
        documentRepository,
        checksumService,
        fileProcessingService,
        vectorChunkStore,
        uploadProperties,
        storageQuotaService,
        filesystemAllowlist,
        boundedDownloader,
        disabledTargetAddressValidator,
        remoteContentProperties,
        folderRepository,
        folderService,
        attachmentExtractor,
        new MailProperties(0, 0, 0, 0),
        new AttachmentExtractionLimiter(limits));
  }

  @Test
  void editorMayUploadADocument() throws IOException {
    grantEditor();
    when(checksumService.computeSha256(any(Path.class))).thenReturn("checksum-123");
    when(documentRepository.findByLibraryIdAndChecksumAndParentDocumentIdIsNull(
            libraryId, "checksum-123"))
        .thenReturn(Optional.empty());

    LibraryDocumentEntry response =
        service.uploadDocument(libraryId, pdfFile("report.pdf", "pdf content"), null, caller);

    // #434: the response reflects the PENDING row created synchronously - not a result from
    // FileProcessingService, which now only runs asynchronously and returns nothing.
    assertThat(response.document().getFileName()).isEqualTo("report.pdf");
    assertThat(response.document().getSourceType()).isEqualTo(DocumentSourceType.UPLOAD);
    assertThat(response.document().getUploadedByUserId()).isEqualTo(currentUserId);
    assertThat(response.document().getStatus()).isEqualTo(DocumentStatus.PENDING);

    // The stored file lives under the library's own subdirectory of the storage path, and async
    // processing was handed exactly that path.
    ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
    verify(fileProcessingService)
        .processUploadedFileAsync(eq(response.document().getId()), pathCaptor.capture());
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
                    libraryId, pdfFile("report.pdf", "pdf content"), null, caller))
        .isInstanceOf(AccessDeniedException.class);

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
                    libraryId, pdfFile("report.pdf", "pdf content"), null, caller))
        .isInstanceOf(NotFoundException.class);
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
                    libraryId, pdfFile("report.pdf", "pdf content"), null, caller))
        .isInstanceOf(NotFoundException.class);
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
                    libraryId, pdfFile("report.pdf", "pdf content"), null, caller))
        .isInstanceOf(ConflictException.class);

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
                    null,
                    caller))
        .isInstanceOf(ValidationException.class);

    assertNoFilesWereStored();
    verify(fileProcessingService, never()).processUploadedFileAsync(any(), any());
  }

  @Test
  void aRealPdfUploadedAsPdfIsAccepted() throws IOException {
    // #435: content detection is a positive check too, not only a rejection path - genuine PDF
    // bytes with a .pdf extension must not be caught by the new guard.
    grantEditor();
    when(checksumService.computeSha256(any(Path.class))).thenReturn("checksum-real-pdf");
    when(documentRepository.findByLibraryIdAndChecksumAndParentDocumentIdIsNull(
            libraryId, "checksum-real-pdf"))
        .thenReturn(Optional.empty());

    LibraryDocumentEntry response =
        service.uploadDocument(libraryId, pdfFile("report.pdf", "%PDF content"), null, caller);

    assertThat(response.document().getFileName()).isEqualTo("report.pdf");
    verify(fileProcessingService).processUploadedFileAsync(eq(response.document().getId()), any());
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
    when(documentRepository.findByLibraryIdAndChecksumAndParentDocumentIdIsNull(
            libraryId, "checksum-docx"))
        .thenReturn(Optional.empty());

    LibraryDocumentEntry response =
        service.uploadDocument(libraryId, realDocxFile("vertrag.docx"), null, caller);

    assertThat(response.document().getFileName()).isEqualTo("vertrag.docx");
    verify(fileProcessingService).processUploadedFileAsync(eq(response.document().getId()), any());
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

    assertThatThrownBy(() -> service.uploadDocument(libraryId, fakePdf, null, caller))
        .isInstanceOf(ValidationException.class)
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
    when(documentRepository.findByLibraryIdAndChecksumAndParentDocumentIdIsNull(
            libraryId, "checksum-md"))
        .thenReturn(Optional.empty());

    MultipartFile markdown =
        new MockMultipartFile(
            "file", "notes.md", "text/markdown", "# Titel\n\nEin Absatz Text.".getBytes());

    LibraryDocumentEntry response = service.uploadDocument(libraryId, markdown, null, caller);

    assertThat(response.document().getFileName()).isEqualTo("notes.md");
    verify(fileProcessingService).processUploadedFileAsync(eq(response.document().getId()), any());
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

    assertThatThrownBy(() -> service.uploadDocument(libraryId, pdfAsTxt, null, caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("entspricht nicht dem Format .txt");

    assertNoFilesWereStored();
    verify(fileProcessingService, never()).processUploadedFileAsync(any(), any());
  }

  @Test
  void aFileOverTheSizeLimitIsRejectedWithoutBeingStored() throws IOException {
    grantEditor();
    String tooBig = "x".repeat(11 * 1024);

    assertThatThrownBy(
            () -> service.uploadDocument(libraryId, pdfFile("big.pdf", tooBig), null, caller))
        .isInstanceOf(PayloadTooLargeException.class);

    assertNoFilesWereStored();
  }

  @Test
  void anUploadThatWouldExceedTheLibraryQuotaIsRejectedWithoutBeingStored() throws IOException {
    // #119: the library's storage quota is checked before anything is written to disk, exactly
    // like the per-file size limit above - a rejected upload must leave the bestand unchanged.
    grantEditor();
    when(storageQuotaService.wouldExceedQuota(eq(libraryId), anyLong())).thenReturn(true);
    when(storageQuotaService.quotaExceededMessage(libraryId))
        .thenReturn("Speicherkontingent der Bibliothek erschöpft (10,0 GB von 10,0 GB belegt)");

    assertThatThrownBy(
            () -> service.uploadDocument(libraryId, pdfFile("report.pdf", "content"), null, caller))
        .isInstanceOf(PayloadTooLargeException.class)
        .hasMessageContaining("Speicherkontingent der Bibliothek erschöpft")
        .hasMessageContaining("10,0 GB von 10,0 GB belegt");

    assertNoFilesWereStored();
    verify(documentRepository, never()).save(any(Document.class));
  }

  @Test
  void aChecksumAlreadyPresentInTheSameLibraryIsRejectedAndTheFileIsRemovedAgain()
      throws IOException {
    grantEditor();
    when(checksumService.computeSha256(any(Path.class))).thenReturn("duplicate-checksum");
    when(documentRepository.findByLibraryIdAndChecksumAndParentDocumentIdIsNull(
            libraryId, "duplicate-checksum"))
        .thenReturn(Optional.of(new Document("existing.pdf", "path", "application/pdf", 5L)));

    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    libraryId, pdfFile("copy.pdf", "same content"), null, caller))
        .isInstanceOf(ConflictException.class);

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
    when(documentRepository.findByLibraryIdAndChecksumAndParentDocumentIdIsNull(
            libraryId, "checksum-retry"))
        .thenReturn(Optional.of(oldFailedDoc));

    LibraryDocumentEntry response =
        service.uploadDocument(libraryId, pdfFile("report.pdf", "pdf content"), null, caller);

    assertThat(response.document().getStatus()).isEqualTo(DocumentStatus.PENDING);
    verify(vectorStore).delete(documentIdFilter(oldFailedDoc.getId()));
    verify(documentRepository).delete(oldFailedDoc);
    assertThat(Files.exists(oldFailedFile))
        .as("The old FAILED row's own file must be cleaned up when it is replaced")
        .isFalse();
    verify(fileProcessingService).processUploadedFileAsync(eq(response.document().getId()), any());
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
    when(documentRepository.findByLibraryIdAndChecksumAndParentDocumentIdIsNull(
            libraryId, "checksum-race"))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class)))
        .thenThrow(new DataIntegrityViolationException("uk_documents_library_checksum"));

    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    libraryId, pdfFile("racer.pdf", "same content"), null, caller))
        .isInstanceOf(ConflictException.class);

    assertNoFilesWereStored();
    verify(fileProcessingService, never()).processUploadedFileAsync(any(), any());
  }

  @Test
  void aFolderDeletedBetweenValidationAndInsertAnswers404NotTheChecksumMessage()
      throws IOException {
    // #821 review round 1, finding 5: fk_documents_folder (migration 062) can fire on the very
    // same save() the checksum race above tests, if folderId - already confirmed to exist by
    // resolveFolder - is deleted by a concurrent request in the narrow window before this INSERT.
    // That must not surface as "Diese Datei ist bereits in dieser Bibliothek vorhanden" - the file
    // was never a duplicate at all.
    grantEditor();
    UUID folderId = UUID.randomUUID();
    LibraryFolder folder = new LibraryFolder(libraryId, null, "Wird-Geloescht", organizationId);
    when(folderRepository.findById(folderId)).thenReturn(Optional.of(folder));
    when(checksumService.computeSha256(any(Path.class))).thenReturn("checksum-fk-race");
    when(documentRepository.findByLibraryIdAndChecksumAndParentDocumentIdIsNull(
            libraryId, "checksum-fk-race"))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "insert failed",
                new org.hibernate.exception.ConstraintViolationException(
                    "violates foreign key constraint",
                    new java.sql.SQLException("test"),
                    "fk_documents_folder")));

    assertThatThrownBy(
            () ->
                service.uploadDocument(libraryId, pdfFile("race.pdf", "content"), folderId, caller))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("inzwischen gelöscht");

    assertNoFilesWereStored();
    verify(fileProcessingService, never()).processUploadedFileAsync(any(), any());
  }

  @Test
  void aPathTraversingFileNameNeverEscapesTheLibraryStorageDirectory() throws IOException {
    grantEditor();
    when(checksumService.computeSha256(any(Path.class))).thenReturn("checksum-xyz");
    when(documentRepository.findByLibraryIdAndChecksumAndParentDocumentIdIsNull(
            libraryId, "checksum-xyz"))
        .thenReturn(Optional.empty());

    LibraryDocumentEntry response =
        service.uploadDocument(
            libraryId,
            new MockMultipartFile(
                "file",
                "../../../../etc/evil.pdf",
                "application/pdf",
                (PDF_MAGIC_BYTES + "content").getBytes()),
            null,
            caller);

    assertThat(response.document().getFileName()).isEqualTo("evil.pdf");
    ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
    verify(fileProcessingService)
        .processUploadedFileAsync(eq(response.document().getId()), pathCaptor.capture());
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
    when(documentRepository.findByLibraryIdAndChecksumAndParentDocumentIdIsNull(
            libraryId, "checksum-queue-full"))
        .thenReturn(Optional.empty());
    doThrow(new TaskRejectedException("queue is full"))
        .when(fileProcessingService)
        .processUploadedFileAsync(any(), any());

    LibraryDocumentEntry response =
        service.uploadDocument(libraryId, pdfFile("report.pdf", "pdf content"), null, caller);

    assertThat(response.document().getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(response.document().getErrorMessage())
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
    when(documentRepository.findByLibraryIdAndChecksumAndParentDocumentIdIsNull(
            libraryId, "checksum-unexpected"))
        .thenReturn(Optional.empty());
    doThrow(new IllegalStateException("submission blew up unexpectedly"))
        .when(fileProcessingService)
        .processUploadedFileAsync(any(), any());

    LibraryDocumentEntry response =
        service.uploadDocument(libraryId, pdfFile("report.pdf", "pdf content"), null, caller);

    assertThat(response.document().getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(response.document().getErrorMessage())
        .isEqualTo("Die Verarbeitung konnte nicht gestartet werden");
    assertNoFilesWereStored();
  }

  @Test
  void failingAnAlreadyPersistedUploadUsesAConditionalUpdateNotASecondSave() throws IOException {
    // #636 review, item 3: failAlreadyPersistedUpload used to call documentRepository.save on the
    // row it had just committed a moment earlier - the same zombie-row failure mode #632 fixed for
    // the connector paths, just narrower here (the window between that commit and this call, e.g.
    // while handing off to processUploadedFileAsync). It must go through the same conditional
    // markFailed(id, errorMessage) UPDATE the asynchronous path already uses, not a second save.
    grantEditor();
    when(checksumService.computeSha256(any(Path.class))).thenReturn("checksum-conditional-update");
    when(documentRepository.findByLibraryIdAndChecksumAndParentDocumentIdIsNull(
            libraryId, "checksum-conditional-update"))
        .thenReturn(Optional.empty());
    doThrow(new IllegalStateException("submission blew up unexpectedly"))
        .when(fileProcessingService)
        .processUploadedFileAsync(any(), any());
    when(documentRepository.markFailed(any(), any())).thenReturn(1);

    LibraryDocumentEntry response =
        service.uploadDocument(libraryId, pdfFile("report.pdf", "pdf content"), null, caller);

    assertThat(response.document().getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(response.document().getErrorMessage())
        .isEqualTo("Die Verarbeitung konnte nicht gestartet werden");
    verify(documentRepository)
        .markFailed(response.document().getId(), "Die Verarbeitung konnte nicht gestartet werden");
    // Exactly the one save from the PENDING row's own creation - never a second one for the FAILED
    // transition.
    verify(documentRepository, org.mockito.Mockito.times(1)).save(any(Document.class));
  }

  @Test
  void failingAnAlreadyPersistedUploadStillRespondsWhenTheRowWasDeletedConcurrently()
      throws IOException {
    // Reproduces the window itself: a concurrent deleteDocument (or a whole library delete)
    // removes the row between uploadDocument's own commit and this call - markFailed then affects
    // zero rows, the same zero-rows-means-gone contract every other conditional UPDATE in this
    // codebase already follows. The caller's HTTP request still gets an answer describing its own
    // upload attempt; nothing is silently re-inserted.
    grantEditor();
    when(checksumService.computeSha256(any(Path.class))).thenReturn("checksum-deleted-mid-flight");
    when(documentRepository.findByLibraryIdAndChecksumAndParentDocumentIdIsNull(
            libraryId, "checksum-deleted-mid-flight"))
        .thenReturn(Optional.empty());
    doThrow(new IllegalStateException("submission blew up unexpectedly"))
        .when(fileProcessingService)
        .processUploadedFileAsync(any(), any());
    when(documentRepository.markFailed(any(), any())).thenReturn(0);

    LibraryDocumentEntry response =
        service.uploadDocument(libraryId, pdfFile("report.pdf", "pdf content"), null, caller);

    assertThat(response.document().getStatus()).isEqualTo(DocumentStatus.FAILED);
    verify(documentRepository, org.mockito.Mockito.times(1)).save(any(Document.class));
  }

  @Test
  void aViewerCannotDelete() {
    grantViewerOnly();

    assertThatThrownBy(() -> service.deleteDocument(libraryId, UUID.randomUUID(), caller))
        .isInstanceOf(AccessDeniedException.class);
    verify(documentRepository, never()).delete(any());
  }

  @Test
  void aUserWithNoAccessAtAllCannotEvenTellTheLibraryExists() {
    grantNoAccess();

    assertThatThrownBy(() -> service.deleteDocument(libraryId, UUID.randomUUID(), caller))
        .isInstanceOf(NotFoundException.class);
    verify(documentRepository, never()).delete(any());
  }

  @Test
  void deletingAMissingDocumentIs404() {
    grantEditor();
    UUID documentId = UUID.randomUUID();
    when(documentRepository.findById(documentId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.deleteDocument(libraryId, documentId, caller))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void deletingADocumentThatBelongsToAnotherLibraryIs404() {
    grantEditor();
    UUID documentId = UUID.randomUUID();
    Document foreignDoc = new Document("other.pdf", "path", "application/pdf", 5L);
    foreignDoc.setLibraryId(UUID.randomUUID());
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(foreignDoc));

    assertThatThrownBy(() -> service.deleteDocument(libraryId, documentId, caller))
        .isInstanceOf(NotFoundException.class);
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

    service.deleteDocument(libraryId, documentId, caller);

    verify(vectorStore).delete(documentIdFilter(doc.getId()));
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

    service.deleteDocument(libraryId, documentId, caller);

    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(documentRepository, vectorStore);
    inOrder.verify(documentRepository).delete(doc);
    inOrder.verify(vectorStore).delete(documentIdFilter(doc.getId()));
  }

  @Test
  void deletingADocumentWithAttachmentsDeletesTheAttachmentRowsFirst() throws IOException {
    // ADR-0022, Entscheidung 3: a document with attachment rows still pointing at it via
    // parent_document_id cannot be deleted first - fk_documents_parent would reject it. This path
    // takes its attachments with it, deleting them before the parent's own row, exactly like
    // StaleDocumentCleanupService's own children-before-parents order.
    grantEditor();
    UUID documentId = UUID.randomUUID();
    Document doc = new Document("eintrag.html", "https://feed.example/entry", "text/html", 7L);
    doc.setLibraryId(libraryId);
    doc.setSourceType(DocumentSourceType.RSS_FEED);
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(doc));

    Document attachment =
        new Document("anlage.pdf", "https://feed.example/anlage.pdf", "application/pdf", 3L);
    attachment.setLibraryId(libraryId);
    attachment.setSourceType(DocumentSourceType.RSS_FEED);
    attachment.setParentDocumentId(doc.getId());
    when(documentRepository.findByParentDocumentId(doc.getId())).thenReturn(List.of(attachment));

    service.deleteDocument(libraryId, documentId, caller);

    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(documentRepository, vectorStore);
    inOrder.verify(documentRepository).delete(attachment);
    inOrder.verify(documentRepository).delete(doc);
    inOrder.verify(vectorStore).delete(documentIdFilter(attachment.getId()));
    inOrder.verify(vectorStore).delete(documentIdFilter(doc.getId()));
  }

  @Test
  void deletingADocumentWithAGrandchildAttachmentDeletesTheWholeChainDeepestFirst()
      throws IOException {
    // #1183: a Mail-in-Mail chain nests an attachment inside an attachment (a forwarded .eml with
    // its own attachment) - descendantsDeepestFirst must walk both levels, not only the direct
    // children deletingADocumentWithAttachmentsDeletesTheAttachmentRowsFirst covers.
    grantEditor();
    UUID documentId = UUID.randomUUID();
    Document outerMail =
        new Document("aussenmail.eml", "https://feed.example/outer", "message/rfc822", 10L);
    outerMail.setLibraryId(libraryId);
    outerMail.setSourceType(DocumentSourceType.RSS_FEED);
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(outerMail));

    Document innerMail =
        new Document(
            "weitergeleitet.eml",
            "https://feed.example/outer/0/weitergeleitet.eml",
            "message/rfc822",
            8L);
    innerMail.setLibraryId(libraryId);
    innerMail.setSourceType(DocumentSourceType.RSS_FEED);
    innerMail.setParentDocumentId(outerMail.getId());
    Document grandchildAttachment =
        new Document(
            "anlage.pdf",
            "https://feed.example/outer/0/weitergeleitet.eml/0/anlage.pdf",
            "application/pdf",
            5L);
    grandchildAttachment.setLibraryId(libraryId);
    grandchildAttachment.setSourceType(DocumentSourceType.RSS_FEED);
    grandchildAttachment.setParentDocumentId(innerMail.getId());
    when(documentRepository.findByParentDocumentId(outerMail.getId()))
        .thenReturn(List.of(innerMail));
    when(documentRepository.findByParentDocumentId(innerMail.getId()))
        .thenReturn(List.of(grandchildAttachment));
    when(documentRepository.findByParentDocumentId(grandchildAttachment.getId()))
        .thenReturn(List.of());

    service.deleteDocument(libraryId, documentId, caller);

    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(documentRepository, vectorStore);
    inOrder.verify(documentRepository).delete(grandchildAttachment);
    inOrder.verify(documentRepository).delete(innerMail);
    inOrder.verify(documentRepository).delete(outerMail);
    inOrder.verify(vectorStore).delete(documentIdFilter(grandchildAttachment.getId()));
    inOrder.verify(vectorStore).delete(documentIdFilter(innerMail.getId()));
    inOrder.verify(vectorStore).delete(documentIdFilter(outerMail.getId()));
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
        .delete(documentIdFilter(doc.getId()));

    service.deleteDocument(libraryId, documentId, caller);

    verify(documentRepository).delete(doc);
    assertThat(Files.exists(storedFile))
        .as("The file must still be deleted even though the vector store cleanup failed")
        .isFalse();
  }

  @Test
  void loadContentRefusesAFilesystemDocumentWhenTheAllowlistNoLongerAllowsItsSourcePath() {
    // #742 review, finding 2: FilesystemPathAllowlist can be narrowed - or emptied entirely, which
    // disables the FILESYSTEM sourceType altogether - after a library was created. A read against a
    // sourcePath that has since fallen outside it must not silently keep succeeding just because
    // the
    // library once passed validation at creation time; both cases boil down to the same
    // isAllowed(sourcePath) == false the FILESYSTEM branch of loadContent must re-check every time.
    // Exercised as a unit test (mocked FilesystemPathAllowlist) rather than in
    // LibraryDocumentServiceIntegrationTest, mirroring why
    // KnowledgeLibraryServiceFilesystemAllowlistTest
    // exists separately from KnowledgeLibraryServiceIntegrationTest: that suite's shared context
    // has
    // a fixed allowlist for the whole run.
    when(accessService.requireRole(any(), eq(currentUserId), eq(false), eq(AssetRole.VIEWER)))
        .thenReturn(AssetRole.VIEWER);
    when(filesystemAllowlist.isAllowed("/data/documents")).thenReturn(false);

    KnowledgeLibrary filesystemLibrary = mock(KnowledgeLibrary.class);
    when(filesystemLibrary.getId()).thenReturn(libraryId);
    when(filesystemLibrary.getOrganizationId()).thenReturn(organizationId);
    when(filesystemLibrary.getSourcePath()).thenReturn("/data/documents");
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(filesystemLibrary));

    Document doc =
        new Document(
            "bericht.txt",
            "/data/documents/bericht.txt",
            "text/plain",
            10L,
            DocumentSourceType.FILESYSTEM);
    doc.setLibraryId(libraryId);
    UUID documentId = UUID.randomUUID();
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(doc));

    assertThatThrownBy(() -> service.loadContent(documentId, caller))
        .isInstanceOf(NotFoundException.class);
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

    service.deleteDocument(libraryId, documentId, caller);

    verify(vectorStore).delete(documentIdFilter(doc.getId()));
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

    service.deleteDocument(libraryId, documentId, caller);

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

  // #747: loadContent for HTTP_DIRECTORY/RSS_FEED documents proxies the original from the stored
  // source URL instead of answering 404 outright (#736's original behaviour, now local-file-only).

  private HttpServer remoteServer;
  private String remoteBaseUrl;

  @AfterEach
  void tearDownRemoteServer() {
    if (remoteServer != null) {
      remoteServer.stop(0);
      remoteServer = null;
    }
  }

  private void startRemoteServer() throws IOException {
    remoteServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    remoteServer.start();
    remoteBaseUrl = "http://127.0.0.1:" + remoteServer.getAddress().getPort();
  }

  // --- #1239: attachment originals are re-extracted, but only where they have no source of their
  // own ---

  @Test
  void loadContentStillProxiesAnRssAttachmentWithItsOwnDownloadUrl() throws IOException {
    // Regression guard: an AttachmentSource.Download attachment (RSS today, Confluence later)
    // carries parent_document_id just like a mail attachment, but its file_path is a real URL -
    // it must keep being fetched from that URL instead of being re-extracted from its parent.
    startRemoteServer();
    remoteServer.createContext(
        "/anlage.pdf",
        exchange -> {
          byte[] bytes = "Anlage der Detailseite".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/pdf");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    when(accessService.requireRole(any(), eq(currentUserId), eq(false), eq(AssetRole.VIEWER)))
        .thenReturn(AssetRole.VIEWER);
    KnowledgeLibrary library = remoteLibrary(null);
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

    Document entry =
        new Document(
            "eintrag.html",
            remoteBaseUrl + "/eintrag",
            "text/html",
            null,
            DocumentSourceType.RSS_FEED);
    entry.setLibraryId(libraryId);
    Document attachment =
        new Document(
            "anlage.pdf",
            remoteBaseUrl + "/anlage.pdf",
            "application/pdf",
            null,
            DocumentSourceType.RSS_FEED);
    attachment.setLibraryId(libraryId);
    attachment.setParentDocumentId(entry.getId());
    UUID documentId = UUID.randomUUID();
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(attachment));
    when(documentRepository.findById(entry.getId())).thenReturn(Optional.of(entry));

    DocumentContent content = service.loadContent(documentId, caller);
    try {
      assertThat(new String(content.stream().readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("Anlage der Detailseite");
    } finally {
      content.stream().close();
    }
    verifyNoInteractions(attachmentExtractor);
  }

  @Test
  void loadContentDeletesTheReExtractedTempFileWhenTheStreamIsClosed() throws IOException {
    Document mail = uploadedMailRow("posteingang.eml");
    Document attachment = mailAttachmentRow(mail, 0, "anlage.txt");
    UUID documentId = stubAttachmentChain(mail, attachment);

    Path extracted = Files.createTempFile("opaa-attachment-test-", ".txt");
    Files.writeString(extracted, "Anhangsinhalt");
    when(attachmentExtractor.extract(any(), eq("posteingang.eml"), eq(0)))
        .thenReturn(new AttachmentExtractor.Extracted(extracted, "anlage.txt"));

    DocumentContent content = service.loadContent(documentId, caller);
    assertThat(new String(content.stream().readAllBytes(), StandardCharsets.UTF_8))
        .isEqualTo("Anhangsinhalt");
    assertThat(extracted).exists();

    content.stream().close();

    assertThat(extracted).doesNotExist();
  }

  @Test
  void loadContentAnswers404WhenAnAncestorBelongsToAnotherLibrary() throws IOException {
    Document mail = uploadedMailRow("fremd.eml");
    mail.setLibraryId(UUID.randomUUID());
    Document attachment = mailAttachmentRow(mail, 0, "anlage.txt");
    UUID documentId = stubAttachmentChain(mail, attachment);

    assertThatThrownBy(() -> service.loadContent(documentId, caller))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Für dieses Dokument steht kein Originaldokument zur Verfügung");
    verifyNoInteractions(attachmentExtractor);
  }

  @Test
  void loadContentExtractsAMailAttachmentOutOfAnEmlThatIsItselfADownloadedRssAttachment()
      throws IOException {
    // A downloaded .eml is an attachment of its RSS entry (real URL as file_path) AND the parent of
    // its own mail attachments (synthetic paths). The chain must end at the .eml - it is fetched
    // from its own URL - instead of climbing on to the entry, whose path it does not embed.
    startRemoteServer();
    AtomicInteger requestsForMail = new AtomicInteger();
    remoteServer.createContext(
        "/post.eml",
        exchange -> {
          requestsForMail.incrementAndGet();
          byte[] bytes = "mail bytes".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "message/rfc822");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    KnowledgeLibrary library = remoteLibrary(null);
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    grantViewerOnUploadLibrary();

    Document entry =
        new Document(
            "eintrag.html",
            remoteBaseUrl + "/eintrag",
            "text/html",
            null,
            DocumentSourceType.RSS_FEED);
    entry.setLibraryId(libraryId);
    Document mail =
        new Document(
            "post.eml",
            remoteBaseUrl + "/post.eml",
            "message/rfc822",
            null,
            DocumentSourceType.RSS_FEED);
    mail.setLibraryId(libraryId);
    mail.setParentDocumentId(entry.getId());
    Document attachment = mailAttachmentRow(mail, 0, "anlage.txt");
    attachment.setSourceType(DocumentSourceType.RSS_FEED);
    when(documentRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
    when(documentRepository.findById(mail.getId())).thenReturn(Optional.of(mail));
    when(documentRepository.findById(attachment.getId())).thenReturn(Optional.of(attachment));

    Path extracted = Files.createTempFile("opaa-attachment-test-", ".txt");
    Files.writeString(extracted, "Anhang der heruntergeladenen Mail");
    when(attachmentExtractor.extract(any(), eq("post.eml"), eq(0)))
        .thenReturn(new AttachmentExtractor.Extracted(extracted, "anlage.txt"));

    DocumentContent content = service.loadContent(attachment.getId(), caller);
    try {
      assertThat(new String(content.stream().readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("Anhang der heruntergeladenen Mail");
    } finally {
      content.stream().close();
    }
    assertThat(requestsForMail.get()).isEqualTo(1);
  }

  @Test
  void loadContentAnswers404WhenTheChainIsDeeperThanTheConfiguredAttachmentDepth()
      throws IOException {
    // Default max-attachment-depth is 5, so a seven-level chain cannot have been indexed at all.
    Document root = uploadedMailRow("tief.eml");
    when(documentRepository.findById(root.getId())).thenReturn(Optional.of(root));
    Document current = root;
    for (int level = 0; level < 7; level++) {
      Document next = mailAttachmentRow(current, 0, "ebene" + level + ".eml");
      when(documentRepository.findById(next.getId())).thenReturn(Optional.of(next));
      current = next;
    }
    grantViewerOnUploadLibrary();
    UUID documentId = current.getId();

    assertThatThrownBy(() -> service.loadContent(documentId, caller))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Für dieses Dokument steht kein Originaldokument zur Verfügung");
    verifyNoInteractions(attachmentExtractor);
  }

  /** An UPLOAD mail row whose stored file actually exists under this library's upload directory. */
  private Document uploadedMailRow(String fileName) throws IOException {
    Path libraryDir = Files.createDirectories(storageDir.resolve(libraryId.toString()));
    Path storedFile = libraryDir.resolve(fileName);
    Files.writeString(storedFile, "mail bytes");
    Document mail =
        new Document(
            fileName,
            storedFile.toString(),
            "message/rfc822",
            Files.size(storedFile),
            DocumentSourceType.UPLOAD);
    mail.setLibraryId(libraryId);
    return mail;
  }

  /** An attachment row of {@code parent}, with the synthetic file_path of ADR-0022. */
  private Document mailAttachmentRow(Document parent, int index, String fileName) {
    Document attachment =
        new Document(
            fileName,
            parent.getFilePath() + "/" + index + "/" + fileName,
            "text/plain",
            13L,
            DocumentSourceType.UPLOAD);
    attachment.setLibraryId(libraryId);
    attachment.setParentDocumentId(parent.getId());
    return attachment;
  }

  private UUID stubAttachmentChain(Document parent, Document attachment) {
    when(documentRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
    when(documentRepository.findById(attachment.getId())).thenReturn(Optional.of(attachment));
    grantViewerOnUploadLibrary();
    return attachment.getId();
  }

  private void grantViewerOnUploadLibrary() {
    when(accessService.requireRole(any(), eq(currentUserId), eq(false), eq(AssetRole.VIEWER)))
        .thenReturn(AssetRole.VIEWER);
  }

  private KnowledgeLibrary remoteLibrary(String sourceCredentials) {
    KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    when(library.getId()).thenReturn(libraryId);
    when(library.getOrganizationId()).thenReturn(organizationId);
    when(library.getSourceCredentials()).thenReturn(sourceCredentials);
    return library;
  }

  private Document remoteDocument(DocumentSourceType sourceType, String url) {
    Document document = new Document("original.pdf", url, null, null, sourceType);
    document.setLibraryId(libraryId);
    return document;
  }

  @Test
  void loadContentProxiesTheOriginalFromTheDocumentsStoredSourceUrl() throws IOException {
    startRemoteServer();
    remoteServer.createContext(
        "/original.pdf",
        exchange -> {
          byte[] bytes =
              "Originalinhalt vom entfernten Quellsystem".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/pdf");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    when(accessService.requireRole(any(), eq(currentUserId), eq(false), eq(AssetRole.VIEWER)))
        .thenReturn(AssetRole.VIEWER);
    KnowledgeLibrary library = remoteLibrary(null);
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    Document document =
        remoteDocument(DocumentSourceType.HTTP_DIRECTORY, remoteBaseUrl + "/original.pdf");
    UUID documentId = UUID.randomUUID();
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

    DocumentContent content = service.loadContent(documentId, caller);

    try {
      assertThat(content.isStreamed()).isTrue();
      assertThat(content.contentType()).isEqualTo("application/pdf");
      assertThat(content.fileName()).isEqualTo("original.pdf");
      assertThat(new String(content.stream().readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("Originalinhalt vom entfernten Quellsystem");
    } finally {
      content.stream().close();
    }
  }

  @Test
  void loadContentSendsTheLibrarysStoredCredentialsToTheSourceButNeverToTheCaller()
      throws IOException {
    startRemoteServer();
    AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    remoteServer.createContext(
        "/original.pdf",
        exchange -> {
          receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    when(accessService.requireRole(any(), eq(currentUserId), eq(false), eq(AssetRole.VIEWER)))
        .thenReturn(AssetRole.VIEWER);
    KnowledgeLibrary library = remoteLibrary("libuser:libpass");
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    Document document =
        remoteDocument(DocumentSourceType.RSS_FEED, remoteBaseUrl + "/original.pdf");
    UUID documentId = UUID.randomUUID();
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

    DocumentContent content = service.loadContent(documentId, caller);

    try {
      String expected =
          "Basic "
              + java.util.Base64.getEncoder()
                  .encodeToString("libuser:libpass".getBytes(StandardCharsets.UTF_8));
      assertThat(receivedAuthorization.get()).isEqualTo(expected);
      // #748 review, nit 1: the previous assertion here (content.toString() lacking "libpass")
      // could never fail - DocumentContent never had a credentials field to begin with. This reads
      // the actual bytes the caller would receive and checks the credentials never leaked into
      // them, the thing this test is meant to guard against.
      assertThat(new String(content.stream().readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("content")
          .doesNotContain("libpass");
    } finally {
      content.stream().close();
    }
  }

  @Test
  void loadContentAnswers404WithAGermanMessageWhenTheRemoteSourceIsUnreachable() {
    when(accessService.requireRole(any(), eq(currentUserId), eq(false), eq(AssetRole.VIEWER)))
        .thenReturn(AssetRole.VIEWER);
    KnowledgeLibrary library = remoteLibrary(null);
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    // Port 1 is a privileged port nothing in this test listens on - the connection is refused
    // immediately, standing in for "the source is offline" without any real network access.
    Document document =
        remoteDocument(DocumentSourceType.HTTP_DIRECTORY, "http://127.0.0.1:1/original.pdf");
    UUID documentId = UUID.randomUUID();
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

    assertThatThrownBy(() -> service.loadContent(documentId, caller))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Für dieses Dokument steht kein Originaldokument zur Verfügung");
  }

  @Test
  void loadContentAnswers404WhenTheAllowlistRejectsTheStoredSourceUrl() throws IOException {
    // #748 review, finding 4: the previous version of this test pointed at
    // "http://127.0.0.1:1/original.pdf" - the exact same unreachable address
    // loadContentAnswers404WithAGermanMessageWhenTheRemoteSourceIsUnreachable above uses to stand
    // in for "the source is offline". Both produced the identical generic 404, so this test stayed
    // green even with the allowlist re-check removed entirely - "blocked" and "unreachable" were
    // indistinguishable. This version instead points at a real, listening local server: with the
    // re-check in place, the request must never even reach it (asserted via requestsReceived
    // below); with it removed, the request would succeed and both assertions would fail.
    startRemoteServer();
    AtomicInteger requestsReceived = new AtomicInteger();
    remoteServer.createContext(
        "/original.pdf",
        exchange -> {
          requestsReceived.incrementAndGet();
          byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    // A dedicated, enabled validator with an empty allowlist stands in for an allowlist that has
    // since been narrowed (or was never configured to include this host) - loopback is always
    // blocked once validation is enabled, regardless of the allowlist.
    TargetAddressValidator enabledValidator = new TargetAddressValidator(true, List.of());
    LibraryDocumentService serviceWithValidation =
        new LibraryDocumentService(
            libraryRepository,
            accessService,
            documentRepository,
            checksumService,
            fileProcessingService,
            vectorChunkStore,
            new UploadProperties(storageDir.toString(), 10L * 1024, null, 0, 0),
            storageQuotaService,
            filesystemAllowlist,
            new BoundedDownloader(enabledValidator),
            enabledValidator,
            remoteContentProperties,
            folderRepository,
            folderService,
            attachmentExtractor,
            new MailProperties(0, 0, 0, 0),
            new AttachmentExtractionLimiter(new AttachmentExtractionProperties(0, null)));
    when(accessService.requireRole(any(), eq(currentUserId), eq(false), eq(AssetRole.VIEWER)))
        .thenReturn(AssetRole.VIEWER);
    KnowledgeLibrary library = remoteLibrary(null);
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    Document document =
        remoteDocument(DocumentSourceType.HTTP_DIRECTORY, remoteBaseUrl + "/original.pdf");
    UUID documentId = UUID.randomUUID();
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

    assertThatThrownBy(() -> serviceWithValidation.loadContent(documentId, caller))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Für dieses Dokument steht kein Originaldokument zur Verfügung");
    assertThat(requestsReceived.get()).isZero();
  }

  // --- #1243: the re-extraction path is serialized per parent and globally capped -------------

  /**
   * #1243: re-extracting an attachment parses the whole parent original, so two clicks on two
   * attachments of the same mail must not parse it twice at the same time. The stubbed extractor
   * records how many extractions overlap; serialized means never more than one.
   */
  @Test
  void loadContentSerializesConcurrentReExtractionsOfTheSameParent() throws Exception {
    Document mail = uploadedMailRow("gleichzeitig.eml");
    Document first = mailAttachmentRow(mail, 0, "anlage-0.txt");
    Document second = mailAttachmentRow(mail, 1, "anlage-1.txt");
    when(documentRepository.findById(mail.getId())).thenReturn(Optional.of(mail));
    when(documentRepository.findById(first.getId())).thenReturn(Optional.of(first));
    when(documentRepository.findById(second.getId())).thenReturn(Optional.of(second));
    grantViewerOnUploadLibrary();

    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger maxInFlight = new AtomicInteger();
    when(attachmentExtractor.extract(any(), eq("gleichzeitig.eml"), anyInt()))
        .thenAnswer(
            invocation -> {
              int concurrent = inFlight.incrementAndGet();
              maxInFlight.accumulateAndGet(concurrent, Math::max);
              try {
                Thread.sleep(200);
              } finally {
                inFlight.decrementAndGet();
              }
              int index = invocation.getArgument(2);
              Path extracted = Files.createTempFile("opaa-attachment-test-", ".txt");
              Files.writeString(extracted, "Anhang " + index);
              return new AttachmentExtractor.Extracted(extracted, "anlage-" + index + ".txt");
            });

    List<DocumentContent> contents =
        loadConcurrently(service, List.of(first.getId(), second.getId()));
    try {
      assertThat(contents).hasSize(2);
      assertThat(maxInFlight.get()).isEqualTo(1);
    } finally {
      closeAll(contents);
    }
  }

  /**
   * #1243: once the instance-wide ceiling is reached, a further re-extraction is refused with a
   * German 503 instead of waiting without limit or piling further parses onto the instance.
   */
  @Test
  void loadContentAnswers503WhenTheConcurrentExtractionCeilingIsReached() throws Exception {
    LibraryDocumentService cappedService =
        serviceWith(new AttachmentExtractionProperties(1, Duration.ofMillis(50)));
    Document firstMail = uploadedMailRow("erste.eml");
    Document secondMail = uploadedMailRow("zweite.eml");
    Document firstAttachment = mailAttachmentRow(firstMail, 0, "anlage-0.txt");
    Document secondAttachment = mailAttachmentRow(secondMail, 0, "anlage-0.txt");
    when(documentRepository.findById(firstMail.getId())).thenReturn(Optional.of(firstMail));
    when(documentRepository.findById(secondMail.getId())).thenReturn(Optional.of(secondMail));
    when(documentRepository.findById(firstAttachment.getId()))
        .thenReturn(Optional.of(firstAttachment));
    when(documentRepository.findById(secondAttachment.getId()))
        .thenReturn(Optional.of(secondAttachment));
    grantViewerOnUploadLibrary();

    CountDownLatch firstExtractionStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstExtraction = new CountDownLatch(1);
    when(attachmentExtractor.extract(any(), eq("erste.eml"), anyInt()))
        .thenAnswer(
            invocation -> {
              firstExtractionStarted.countDown();
              assertThat(releaseFirstExtraction.await(10, TimeUnit.SECONDS)).isTrue();
              Path extracted = Files.createTempFile("opaa-attachment-test-", ".txt");
              Files.writeString(extracted, "Anhang");
              return new AttachmentExtractor.Extracted(extracted, "anlage-0.txt");
            });

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<DocumentContent> holder =
          executor.submit(() -> cappedService.loadContent(firstAttachment.getId(), caller));
      assertThat(firstExtractionStarted.await(10, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> cappedService.loadContent(secondAttachment.getId(), caller))
          .isInstanceOf(ServiceUnavailableException.class)
          .hasMessage(
              "Es werden gerade zu viele Anhänge geöffnet."
                  + " Bitte versuchen Sie es in einem Moment erneut.");
      // The refused request never reached the extraction itself.
      verify(attachmentExtractor, never()).extract(any(), eq("zweite.eml"), anyInt());

      releaseFirstExtraction.countDown();
      DocumentContent content = holder.get(10, TimeUnit.SECONDS);
      content.stream().close();
    } finally {
      releaseFirstExtraction.countDown();
      executor.shutdownNow();
    }
  }

  private List<DocumentContent> loadConcurrently(
      LibraryDocumentService target, List<UUID> documentIds) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(documentIds.size());
    try {
      List<Future<DocumentContent>> futures =
          documentIds.stream()
              .map(id -> executor.submit(() -> target.loadContent(id, caller)))
              .toList();
      List<DocumentContent> contents = new ArrayList<>(futures.size());
      for (Future<DocumentContent> future : futures) {
        contents.add(future.get(30, TimeUnit.SECONDS));
      }
      return contents;
    } finally {
      executor.shutdownNow();
    }
  }

  private static void closeAll(List<DocumentContent> contents) throws IOException {
    for (DocumentContent content : contents) {
      content.stream().close();
    }
  }
}
