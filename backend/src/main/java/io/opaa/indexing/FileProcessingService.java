package io.opaa.indexing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;

public class FileProcessingService {

  private static final Logger log = LoggerFactory.getLogger(FileProcessingService.class);

  private final DocumentService documentService;
  private final ChunkingService chunkingService;
  private final DocumentRepository documentRepository;
  private final VectorStore vectorStore;
  private final ChecksumService checksumService;

  public FileProcessingService(
      DocumentService documentService,
      ChunkingService chunkingService,
      DocumentRepository documentRepository,
      VectorStore vectorStore,
      ChecksumService checksumService) {
    this.documentService = documentService;
    this.chunkingService = chunkingService;
    this.documentRepository = documentRepository;
    this.vectorStore = vectorStore;
    this.checksumService = checksumService;
  }

  public FileProcessingResult processFile(Path file) throws IOException {
    String filePath = file.toAbsolutePath().toString();
    String fileName = file.getFileName().toString();

    // Compute checksum before any processing
    String checksum = checksumService.computeSha256(file);

    // Check if document already exists
    Optional<Document> existing = documentRepository.findByFilePath(filePath);
    if (existing.isPresent()) {
      Document existingDoc = existing.get();
      if (checksum.equals(existingDoc.getChecksum())
          && existingDoc.getStatus() == DocumentStatus.INDEXED) {
        log.info("Skipping unchanged document: {}", fileName);
        return FileProcessingResult.SKIPPED;
      }
      // Document changed or was not successfully indexed — delete old data
      vectorStore.delete("document_id == '" + existingDoc.getId().toString() + "'");
      documentRepository.delete(existingDoc);
    }

    String contentType = Files.probeContentType(file);
    long fileSize = Files.size(file);

    var doc = new Document(fileName, filePath, contentType, fileSize);
    doc = documentRepository.save(doc);

    try {
      // Parse document using Tika
      List<org.springframework.ai.document.Document> parsed = documentService.parseDocument(file);
      if (parsed.isEmpty()) {
        log.warn("No content extracted from: {}", file);
        doc.setStatus(DocumentStatus.FAILED);
        documentRepository.save(doc);
        return FileProcessingResult.PROCESSED;
      }

      // Chunk the parsed content
      List<org.springframework.ai.document.Document> chunks =
          chunkingService.chunkDocuments(fileName, parsed);
      log.debug("File {} produced {} chunks", fileName, chunks.size());

      // Enrich chunks with metadata and store via VectorStore
      storeChunks(doc, chunks);

      doc.setChunkCount(chunks.size());
      doc.setIndexedAt(Instant.now());
      doc.setChecksum(checksum);
      doc.setStatus(DocumentStatus.INDEXED);
      documentRepository.save(doc);
    } catch (Exception e) {
      doc.setStatus(DocumentStatus.FAILED);
      documentRepository.save(doc);
      throw e;
    }

    return FileProcessingResult.PROCESSED;
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
