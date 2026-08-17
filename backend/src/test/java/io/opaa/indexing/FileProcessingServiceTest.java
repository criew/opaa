package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.observability.IndexingMetrics;
import io.opaa.organization.Organization;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;

@ExtendWith(MockitoExtension.class)
class FileProcessingServiceTest {

  @Mock private DocumentService documentService;
  @Mock private ChunkingService chunkingService;
  @Mock private DocumentRepository documentRepository;
  @Mock private VectorStore vectorStore;
  @Mock private ChecksumService checksumService;

  @TempDir Path tempDir;

  private FileProcessingService service;

  @BeforeEach
  void setUp() {
    service =
        new FileProcessingService(
            documentService,
            chunkingService,
            documentRepository,
            vectorStore,
            checksumService,
            new IndexingMetrics(new SimpleMeterRegistry()));
  }

  @Test
  void firstRunProcessesDocument() throws IOException {
    Path file = tempDir.resolve("new-doc.txt");
    Files.writeString(file, "some content");

    when(checksumService.computeSha256(file)).thenReturn("abc123");
    when(documentRepository.findByFilePath(file.toAbsolutePath().toString()))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("new-doc.txt"), eq(parsed))).thenReturn(chunks);

    FileProcessingResult result = service.processFile(file);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    verify(documentService).parseDocument(file);
    verify(chunkingService).chunkDocuments(eq("new-doc.txt"), eq(parsed));
    verify(vectorStore).add(any());

    // Verify checksum was saved (save is called twice: initial PENDING + final INDEXED)
    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository, org.mockito.Mockito.times(2)).save(docCaptor.capture());
    Document lastSaved = docCaptor.getAllValues().getLast();
    assertThat(lastSaved.getChecksum()).isEqualTo("abc123");
    assertThat(lastSaved.getStatus()).isEqualTo(DocumentStatus.INDEXED);
  }

  @Test
  void newDocumentAndItsChunksCarryTheSystemLibraryAndOrganizationAsMetadata() throws IOException {
    // #201 acceptance criteria: every document belongs to exactly one library, and every chunk
    // carries library_id and organization_id. Indexing currently always targets the single
    // system library (see FileProcessingService's Javadoc on why); this pins that both the
    // document row and the chunk metadata actually carry it, not just one of the two.
    Path file = tempDir.resolve("library-metadata.txt");
    Files.writeString(file, "some content");

    when(checksumService.computeSha256(file)).thenReturn("abc123");
    when(documentRepository.findByFilePath(file.toAbsolutePath().toString()))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("library-metadata.txt"), eq(parsed))).thenReturn(chunks);

    service.processFile(file);

    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository, org.mockito.Mockito.atLeast(1)).save(docCaptor.capture());
    Document savedDoc = docCaptor.getAllValues().getFirst();
    assertThat(savedDoc.getLibraryId()).isEqualTo(KnowledgeLibrary.SYSTEM_LIBRARY_ID);
    assertThat(savedDoc.getOrganizationId()).isEqualTo(Organization.DEFAULT_ID);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<org.springframework.ai.document.Document>> chunkCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(vectorStore).add(chunkCaptor.capture());
    org.springframework.ai.document.Document storedChunk = chunkCaptor.getValue().getFirst();
    Map<String, Object> metadata = storedChunk.getMetadata();
    assertThat(metadata).containsEntry("library_id", KnowledgeLibrary.SYSTEM_LIBRARY_ID.toString());
    assertThat(metadata).containsEntry("organization_id", Organization.DEFAULT_ID.toString());
  }

  @Test
  void skipsUnchangedDocumentWithSameChecksumAndIndexedStatus() throws IOException {
    Path file = tempDir.resolve("unchanged.txt");
    Files.writeString(file, "same content");

    when(checksumService.computeSha256(file)).thenReturn("matching-checksum");

    Document existingDoc =
        new Document("unchanged.txt", file.toAbsolutePath().toString(), null, 0L);
    existingDoc.setChecksum("matching-checksum");
    existingDoc.setStatus(DocumentStatus.INDEXED);
    when(documentRepository.findByFilePath(file.toAbsolutePath().toString()))
        .thenReturn(Optional.of(existingDoc));

    FileProcessingResult result = service.processFile(file);

    assertThat(result).isEqualTo(FileProcessingResult.SKIPPED);
    verify(documentService, never()).parseDocument(any());
    verify(chunkingService, never()).chunkDocuments(anyString(), any());
    verify(vectorStore, never()).add(any());
    verify(vectorStore, never()).delete(anyString());
  }

  @Test
  void reindexesDocumentWithChangedChecksum() throws IOException {
    Path file = tempDir.resolve("changed.txt");
    Files.writeString(file, "new content");

    when(checksumService.computeSha256(file)).thenReturn("new-checksum");

    Document existingDoc = new Document("changed.txt", file.toAbsolutePath().toString(), null, 10L);
    existingDoc.setChecksum("old-checksum");
    existingDoc.setStatus(DocumentStatus.INDEXED);
    when(documentRepository.findByFilePath(file.toAbsolutePath().toString()))
        .thenReturn(Optional.of(existingDoc));
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("changed.txt"), eq(parsed))).thenReturn(chunks);

    FileProcessingResult result = service.processFile(file);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    verify(vectorStore).delete("document_id == '" + existingDoc.getId().toString() + "'");
    verify(documentRepository).delete(existingDoc);
    verify(documentService).parseDocument(file);
  }

  @Test
  void reindexingKeepsTheLibraryAssignment() throws IOException {
    // #201 acceptance criteria: re-indexing keeps the library assignment. The old document row is
    // deleted and a new one created (see reindexesDocumentWithChangedChecksum above), so this pins
    // that the replacement row still carries the system library, not a dangling/absent one.
    Path file = tempDir.resolve("reindexed.txt");
    Files.writeString(file, "new content");

    when(checksumService.computeSha256(file)).thenReturn("new-checksum");

    Document existingDoc =
        new Document("reindexed.txt", file.toAbsolutePath().toString(), null, 10L);
    existingDoc.setLibraryId(KnowledgeLibrary.SYSTEM_LIBRARY_ID);
    existingDoc.setOrganizationId(Organization.DEFAULT_ID);
    existingDoc.setChecksum("old-checksum");
    existingDoc.setStatus(DocumentStatus.INDEXED);
    when(documentRepository.findByFilePath(file.toAbsolutePath().toString()))
        .thenReturn(Optional.of(existingDoc));
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("reindexed.txt"), eq(parsed))).thenReturn(chunks);

    service.processFile(file);

    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository, org.mockito.Mockito.atLeast(1)).save(docCaptor.capture());
    Document newDoc = docCaptor.getAllValues().getFirst();
    assertThat(newDoc.getId()).isNotEqualTo(existingDoc.getId());
    assertThat(newDoc.getLibraryId()).isEqualTo(KnowledgeLibrary.SYSTEM_LIBRARY_ID);
    assertThat(newDoc.getOrganizationId()).isEqualTo(Organization.DEFAULT_ID);
  }

  @Test
  void reindexesDocumentWithNullChecksum() throws IOException {
    Path file = tempDir.resolve("legacy.txt");
    Files.writeString(file, "legacy content");

    when(checksumService.computeSha256(file)).thenReturn("computed-checksum");

    Document existingDoc = new Document("legacy.txt", file.toAbsolutePath().toString(), null, 10L);
    existingDoc.setStatus(DocumentStatus.INDEXED);
    // checksum is null (legacy document without checksum)
    when(documentRepository.findByFilePath(file.toAbsolutePath().toString()))
        .thenReturn(Optional.of(existingDoc));
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("legacy.txt"), eq(parsed))).thenReturn(chunks);

    FileProcessingResult result = service.processFile(file);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    verify(vectorStore).delete("document_id == '" + existingDoc.getId().toString() + "'");
    verify(documentRepository).delete(existingDoc);
  }

  @Test
  void reindexesDocumentWithFailedStatusEvenIfChecksumMatches() throws IOException {
    Path file = tempDir.resolve("failed.txt");
    Files.writeString(file, "failed content");

    when(checksumService.computeSha256(file)).thenReturn("same-checksum");

    Document existingDoc = new Document("failed.txt", file.toAbsolutePath().toString(), null, 10L);
    existingDoc.setChecksum("same-checksum");
    existingDoc.setStatus(DocumentStatus.FAILED);
    when(documentRepository.findByFilePath(file.toAbsolutePath().toString()))
        .thenReturn(Optional.of(existingDoc));
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("failed.txt"), eq(parsed))).thenReturn(chunks);

    FileProcessingResult result = service.processFile(file);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    verify(documentService).parseDocument(file);
  }

  @Test
  void processUrlFileIndexesNewUrlDocument() throws IOException {
    Path file = tempDir.resolve("remote-doc.pdf");
    Files.writeString(file, "pdf content");

    when(checksumService.computeSha256(file)).thenReturn("sha256-of-pdf");
    when(documentRepository.findByFilePath("https://example.com/docs/remote-doc.pdf"))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("remote-doc.pdf"), eq(parsed))).thenReturn(chunks);

    FileProcessingResult result =
        service.processUrlFile(
            file,
            "remote-doc.pdf",
            "https://example.com/docs/remote-doc.pdf",
            "2025-06-15 10:30",
            1024);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    verify(documentService).parseDocument(file);
    verify(vectorStore).add(any());

    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository, org.mockito.Mockito.atLeast(1)).save(docCaptor.capture());
    Document lastSaved = docCaptor.getAllValues().getLast();
    assertThat(lastSaved.getChecksum()).isEqualTo("sha256-of-pdf");
    assertThat(lastSaved.getLastModifiedRemote()).isEqualTo("2025-06-15 10:30");
    assertThat(lastSaved.getStatus()).isEqualTo(DocumentStatus.INDEXED);
  }

  @Test
  void processUrlFileUsesOriginalFilenameNotTempFilename() throws IOException {
    // Reproduces: URL indexer stores temp filename (opaa-xxx.pdf) instead of original filename
    Path tempFile = Files.createTempFile(tempDir, "opaa-", ".pdf");
    Files.writeString(tempFile, "pdf content");
    String originalFileName = "my-report.pdf";

    when(checksumService.computeSha256(tempFile)).thenReturn("sha256-of-pdf");
    when(documentRepository.findByFilePath("https://example.com/docs/my-report.pdf"))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(tempFile)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq(originalFileName), eq(parsed))).thenReturn(chunks);

    FileProcessingResult result =
        service.processUrlFile(
            tempFile,
            originalFileName,
            "https://example.com/docs/my-report.pdf",
            "2025-06-15 10:30",
            1024);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);

    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository, org.mockito.Mockito.atLeast(1)).save(docCaptor.capture());
    Document firstSaved = docCaptor.getAllValues().getFirst();
    assertThat(firstSaved.getFileName())
        .as("Document must store original filename, not temp filename")
        .isEqualTo(originalFileName);
    assertThat(firstSaved.getFileName()).doesNotStartWith("opaa-");
  }

  @Test
  void processUrlFileSkipsUnchangedDocument() throws IOException {
    Path file = tempDir.resolve("unchanged-url.pdf");
    Files.writeString(file, "pdf content");

    when(checksumService.computeSha256(file)).thenReturn("same-sha256");

    Document existingDoc =
        new Document(
            "unchanged-url.pdf",
            "https://example.com/docs/unchanged-url.pdf",
            null,
            1024L,
            DocumentSourceType.HTTP_DIRECTORY);
    existingDoc.setChecksum("same-sha256");
    existingDoc.setStatus(DocumentStatus.INDEXED);

    when(documentRepository.findByFilePath("https://example.com/docs/unchanged-url.pdf"))
        .thenReturn(Optional.of(existingDoc));

    FileProcessingResult result =
        service.processUrlFile(
            file,
            "unchanged-url.pdf",
            "https://example.com/docs/unchanged-url.pdf",
            "2025-06-15 10:30",
            1024);

    assertThat(result).isEqualTo(FileProcessingResult.SKIPPED);
    verify(documentService, never()).parseDocument(any());
  }

  @Test
  void processUrlFileReindexesChangedDocument() throws IOException {
    Path file = tempDir.resolve("changed-url.pdf");
    Files.writeString(file, "new pdf content");

    when(checksumService.computeSha256(file)).thenReturn("new-sha256");

    Document existingDoc =
        new Document(
            "changed-url.pdf",
            "https://example.com/docs/changed-url.pdf",
            null,
            1024L,
            DocumentSourceType.HTTP_DIRECTORY);
    existingDoc.setChecksum("old-sha256");
    existingDoc.setStatus(DocumentStatus.INDEXED);

    when(documentRepository.findByFilePath("https://example.com/docs/changed-url.pdf"))
        .thenReturn(Optional.of(existingDoc));
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("changed-url.pdf"), eq(parsed))).thenReturn(chunks);

    FileProcessingResult result =
        service.processUrlFile(
            file,
            "changed-url.pdf",
            "https://example.com/docs/changed-url.pdf",
            "2025-06-15 10:30",
            2048);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    verify(vectorStore).delete("document_id == '" + existingDoc.getId().toString() + "'");
    verify(documentRepository).delete(existingDoc);
    verify(documentService).parseDocument(file);
  }

  @Test
  void processUploadedFileIndexesDocumentWithLibraryAndUploaderMetadata() throws IOException {
    Path file = tempDir.resolve("upload.pdf");
    Files.writeString(file, "uploaded pdf content");

    UUID libraryId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    UUID uploaderId = UUID.randomUUID();

    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("upload.pdf"), eq(parsed))).thenReturn(chunks);

    Document result =
        service.processUploadedFile(
            file, "upload.pdf", "checksum-abc", libraryId, organizationId, uploaderId);

    assertThat(result.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(result.getSourceType()).isEqualTo(DocumentSourceType.UPLOAD);
    assertThat(result.getLibraryId()).isEqualTo(libraryId);
    assertThat(result.getOrganizationId()).isEqualTo(organizationId);
    assertThat(result.getUploadedByUserId()).isEqualTo(uploaderId);
    assertThat(result.getChecksum()).isEqualTo("checksum-abc");
    assertThat(result.getChunkCount()).isEqualTo(1);
    verify(vectorStore).add(any());
    // The upload path never looks the document up by file path - dedup for uploads is scoped per
    // library and already decided by the caller before this method runs (see the class Javadoc).
    verify(documentRepository, never()).findByFilePath(anyString());
  }

  @Test
  void processUploadedFileThrowsWithoutPersistingARowWhenNoContentExtracted() throws IOException {
    // #420 code review, nit 6: unlike processFile/processUrlFile, a failed upload leaves no row
    // behind at all - nothing is gained by listing a FAILED document whose file the caller is
    // about to delete.
    Path file = tempDir.resolve("empty-upload.pdf");
    Files.writeString(file, "");

    when(documentService.parseDocument(file)).thenReturn(List.of());

    org.junit.jupiter.api.Assertions.assertThrows(
        EmptyDocumentContentException.class,
        () ->
            service.processUploadedFile(
                file,
                "empty-upload.pdf",
                "checksum-empty",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()));

    verify(documentRepository, never()).save(any(Document.class));
    verify(vectorStore, never()).add(any());
  }

  @Test
  void processUploadedFileDeletesTheRowAgainWhenChunkingFailsAfterAnInitialSave()
      throws IOException {
    Path file = tempDir.resolve("upload-that-fails-later.pdf");
    Files.writeString(file, "content that parses but fails to chunk");

    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);
    RuntimeException chunkingFailure = new RuntimeException("chunking blew up");
    when(chunkingService.chunkDocuments(eq("upload-that-fails-later.pdf"), eq(parsed)))
        .thenThrow(chunkingFailure);

    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class,
        () ->
            service.processUploadedFile(
                file,
                "upload-that-fails-later.pdf",
                "checksum-xyz",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()));

    // Persisted once (the initial PENDING row, since parsing did succeed), then removed again -
    // no orphaned FAILED row with a dead file_path is left behind.
    verify(documentRepository, org.mockito.Mockito.times(1)).save(any(Document.class));
    verify(documentRepository).delete(any(Document.class));
    // Nothing was ever written to the vector store here (chunkDocuments itself threw, before
    // storeChunks could run) - the catch block's vectorStore.delete call is still made
    // unconditionally, the same way processFile/processUrlFile's re-index paths always call it
    // regardless of whether there was anything to remove.
    verify(vectorStore, never()).add(any());
    verify(vectorStore).delete(anyString());
  }

  @Test
  void processUploadedFileSettlesAConcurrentDuplicateAtTheFirstSaveBeforeAnyEmbeddingWork()
      throws IOException {
    // #420 second code review round, finding 1: the checksum must be set on the FIRST save, so a
    // concurrent duplicate upload (uk_documents_library_checksum, migration 020) is rejected right
    // there - before chunking or embedding ever starts, not after the loser has already written
    // chunks to the vector store.
    Path file = tempDir.resolve("racer.pdf");
    Files.writeString(file, "raced content");

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);
    org.springframework.dao.DataIntegrityViolationException uniqueViolation =
        new org.springframework.dao.DataIntegrityViolationException(
            "uk_documents_library_checksum");
    when(documentRepository.save(any(Document.class))).thenThrow(uniqueViolation);

    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            service.processUploadedFile(
                file,
                "racer.pdf",
                "checksum-race",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()));

    verify(chunkingService, never()).chunkDocuments(anyString(), any());
    verify(vectorStore, never()).add(any());
    // The first save's own failure is not caught locally (it happens before the try block) - the
    // caller (LibraryDocumentService) maps the propagated exception straight to 409, and there is
    // nothing here to clean up: the losing row never committed, and no chunks were ever written.
    verify(vectorStore, never()).delete(anyString());
    verify(documentRepository, never()).delete(any());
  }

  @Test
  void processUploadedFileRemovesAlreadyWrittenChunksWhenTheFinalSaveFails() throws IOException {
    // The rarer failure case the same catch block also has to cover (#420 second code review
    // round, finding 1): parsing and chunking succeed, storeChunks has already written chunks to
    // the vector store, and only the final save (chunkCount/indexedAt/status) fails.
    Path file = tempDir.resolve("fails-on-final-save.pdf");
    Files.writeString(file, "content that makes it all the way to the final save");

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);
    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("fails-on-final-save.pdf"), eq(parsed)))
        .thenReturn(chunks);
    RuntimeException finalSaveFailure = new RuntimeException("final save blew up");
    when(documentRepository.save(any(Document.class)))
        .thenAnswer(inv -> inv.getArgument(0))
        .thenThrow(finalSaveFailure);

    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class,
        () ->
            service.processUploadedFile(
                file,
                "fails-on-final-save.pdf",
                "checksum-final-save-fails",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()));

    // storeChunks did run (vectorStore.add was called) before the final save failed - the catch
    // block must remove exactly those chunks, keyed by this document's id, or they become
    // orphaned: still returned by /api/v1/query, unreachable through deleteDocument (which needs a
    // row to key off of, and the row is gone).
    verify(vectorStore).add(any());
    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository, org.mockito.Mockito.atLeast(1)).save(docCaptor.capture());
    UUID documentId = docCaptor.getAllValues().getFirst().getId();
    verify(vectorStore).delete("document_id == '" + documentId + "'");
    verify(documentRepository).delete(any(Document.class));
  }
}
