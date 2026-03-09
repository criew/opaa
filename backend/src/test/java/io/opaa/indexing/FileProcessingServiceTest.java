package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.observability.IndexingMetrics;
import io.opaa.workspace.Workspace;
import io.opaa.workspace.WorkspaceProperties;
import io.opaa.workspace.WorkspaceRepository;
import io.opaa.workspace.WorkspaceType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
  @Mock private WorkspaceRepository workspaceRepository;

  static final UUID DEFAULT_WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private final WorkspaceProperties workspaceProperties =
      new WorkspaceProperties(
          new WorkspaceProperties.DefaultWorkspace("Default", "Default shared workspace"));

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
            new IndexingMetrics(new SimpleMeterRegistry()),
            workspaceRepository,
            workspaceProperties);
  }

  private Workspace defaultWorkspace() {
    Workspace ws =
        new Workspace(
            "Default", "Default shared workspace", WorkspaceType.SHARED, DEFAULT_WORKSPACE_ID);
    return ws;
  }

  @Test
  void firstRunProcessesDocument() throws IOException {
    Path file = tempDir.resolve("new-doc.txt");
    Files.writeString(file, "some content");

    when(checksumService.computeSha256(file)).thenReturn("abc123");
    when(documentRepository.findByFilePath(file.toAbsolutePath().toString()))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
    Workspace defaultWs = defaultWorkspace();
    when(workspaceRepository.findByNameIgnoreCase("Default")).thenReturn(Optional.of(defaultWs));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("new-doc.txt"), eq(parsed))).thenReturn(chunks);

    FileProcessingResult result = service.processFile(file);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    verify(documentService).parseDocument(file);
    verify(chunkingService).chunkDocuments(eq("new-doc.txt"), eq(parsed));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<org.springframework.ai.document.Document>> chunksCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(vectorStore).add(chunksCaptor.capture());
    List<org.springframework.ai.document.Document> stored = chunksCaptor.getValue();
    assertThat(stored).hasSize(1);
    assertThat(stored.getFirst().getMetadata())
        .containsEntry("workspace_id", defaultWs.getId().toString());

    // Verify checksum was saved (save is called twice: initial PENDING + final INDEXED)
    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository, org.mockito.Mockito.times(2)).save(docCaptor.capture());
    Document lastSaved = docCaptor.getAllValues().getLast();
    assertThat(lastSaved.getChecksum()).isEqualTo("abc123");
    assertThat(lastSaved.getStatus()).isEqualTo(DocumentStatus.INDEXED);
  }

  @Test
  void chunksStoredWithoutWorkspaceIdWhenDefaultWorkspaceNotFound() throws IOException {
    Path file = tempDir.resolve("no-workspace.txt");
    Files.writeString(file, "content");

    when(checksumService.computeSha256(file)).thenReturn("csum");
    when(documentRepository.findByFilePath(file.toAbsolutePath().toString()))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
    when(workspaceRepository.findByNameIgnoreCase("Default")).thenReturn(Optional.empty());

    var parsed = List.of(new org.springframework.ai.document.Document("text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);
    var chunks = List.of(new org.springframework.ai.document.Document("chunk"));
    when(chunkingService.chunkDocuments(anyString(), any())).thenReturn(chunks);

    service.processFile(file);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<org.springframework.ai.document.Document>> chunksCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(vectorStore).add(chunksCaptor.capture());
    assertThat(chunksCaptor.getValue().getFirst().getMetadata()).doesNotContainKey("workspace_id");
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
  void processUrlFileUsesOriginalFilenameNotTempFilename() throws IOException {
    // Simulates the bug: local temp file has a random name, but stored document should use
    // the original filename from the remote server
    Path tempFile = tempDir.resolve("opaa-1234567890.pdf");
    Files.writeString(tempFile, "pdf content");

    String originalFileName = "my-document.pdf";
    String remoteUrl = "https://example.com/docs/my-document.pdf";

    when(checksumService.computeSha256(tempFile)).thenReturn("sha256");
    when(documentRepository.findByFilePath(remoteUrl)).thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(tempFile)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq(originalFileName), eq(parsed))).thenReturn(chunks);

    service.processUrlFile(tempFile, remoteUrl, "2025-06-15 10:30", 1024, originalFileName);

    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository, org.mockito.Mockito.atLeast(1)).save(docCaptor.capture());
    Document firstSaved = docCaptor.getAllValues().getFirst();
    assertThat(firstSaved.getFileName()).isEqualTo(originalFileName);
    assertThat(firstSaved.getFileName()).doesNotContain("opaa-1234567890");
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
            "https://example.com/docs/remote-doc.pdf",
            "2025-06-15 10:30",
            1024,
            "remote-doc.pdf");

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
            "https://example.com/docs/unchanged-url.pdf",
            "2025-06-15 10:30",
            1024,
            "unchanged-url.pdf");

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
            "https://example.com/docs/changed-url.pdf",
            "2025-06-15 10:30",
            2048,
            "changed-url.pdf");

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    verify(vectorStore).delete("document_id == '" + existingDoc.getId().toString() + "'");
    verify(documentRepository).delete(existingDoc);
    verify(documentService).parseDocument(file);
  }
}
