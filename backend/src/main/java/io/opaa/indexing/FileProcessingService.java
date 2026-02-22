package io.opaa.indexing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;

public class FileProcessingService {

  private static final Logger log = LoggerFactory.getLogger(FileProcessingService.class);

  private final DocumentService documentService;
  private final ChunkingService chunkingService;
  private final DocumentRepository documentRepository;
  private final VectorStore vectorStore;

  public FileProcessingService(
      DocumentService documentService,
      ChunkingService chunkingService,
      DocumentRepository documentRepository,
      VectorStore vectorStore) {
    this.documentService = documentService;
    this.chunkingService = chunkingService;
    this.documentRepository = documentRepository;
    this.vectorStore = vectorStore;
  }

  public void processFile(Path file) throws IOException {
    String filePath = file.toAbsolutePath().toString();
    String fileName = file.getFileName().toString();
    String contentType = Files.probeContentType(file);
    long fileSize = Files.size(file);

    // Check if document already exists and delete old chunks
    documentRepository
        .findByFilePath(filePath)
        .ifPresent(
            existing -> {
              vectorStore.delete("document_id == '" + existing.getId().toString() + "'");
              documentRepository.delete(existing);
            });

    var doc = new Document(fileName, filePath, contentType, fileSize);
    doc = documentRepository.save(doc);

    try {
      // Parse document using Tika
      List<org.springframework.ai.document.Document> parsed = documentService.parseDocument(file);
      if (parsed.isEmpty()) {
        log.warn("No content extracted from: {}", file);
        doc.setStatus(DocumentStatus.FAILED);
        documentRepository.save(doc);
        return;
      }

      // Chunk the parsed content
      List<org.springframework.ai.document.Document> chunks =
          chunkingService.chunkDocuments(parsed);
      log.debug("File {} produced {} chunks", fileName, chunks.size());

      // Enrich chunks with metadata and store via VectorStore
      storeChunks(doc, chunks);

      doc.setChunkCount(chunks.size());
      doc.setIndexedAt(Instant.now());
      doc.setStatus(DocumentStatus.INDEXED);
      documentRepository.save(doc);
    } catch (Exception e) {
      doc.setStatus(DocumentStatus.FAILED);
      documentRepository.save(doc);
      throw e;
    }
  }

  private void storeChunks(
      Document document, List<org.springframework.ai.document.Document> chunks) {
    List<org.springframework.ai.document.Document> enriched =
        chunks.stream()
            .map(
                chunk -> {
                  int index = chunks.indexOf(chunk);
                  return new org.springframework.ai.document.Document(
                      chunk.getText(),
                      Map.of(
                          "document_id", document.getId().toString(),
                          "chunk_index", index,
                          "file_name", document.getFileName()));
                })
            .toList();

    vectorStore.add(enriched);
  }
}
