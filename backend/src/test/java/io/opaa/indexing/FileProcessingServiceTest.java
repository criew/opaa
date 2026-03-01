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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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
}
