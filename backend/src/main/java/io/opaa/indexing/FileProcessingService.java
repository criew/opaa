package io.opaa.indexing;

import io.opaa.observability.IndexingMetrics;
import io.opaa.workspace.WorkspaceProperties;
import io.opaa.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
  private final IndexingMetrics metrics;
  private final WorkspaceRepository workspaceRepository;
  private final WorkspaceProperties workspaceProperties;

  public FileProcessingService(
      DocumentService documentService,
      ChunkingService chunkingService,
      DocumentRepository documentRepository,
      VectorStore vectorStore,
      ChecksumService checksumService,
      IndexingMetrics metrics,
      WorkspaceRepository workspaceRepository,
      WorkspaceProperties workspaceProperties) {
    this.documentService = documentService;
    this.chunkingService = chunkingService;
    this.documentRepository = documentRepository;
    this.vectorStore = vectorStore;
    this.checksumService = checksumService;
    this.metrics = metrics;
    this.workspaceRepository = workspaceRepository;
    this.workspaceProperties = workspaceProperties;
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
        metrics.recordSkipped();
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
      metrics.recordFailed();
      throw e;
    }

    metrics.recordProcessed();
    return FileProcessingResult.PROCESSED;
  }

  /**
   * Processes a file downloaded from a remote URL. Uses SHA-256 checksum on the downloaded file for
   * content-based change detection and deduplication. The lastModified date from the directory
   * listing is used upstream (in UrlIndexingExecutor) to skip downloads entirely when unchanged.
   */
  public FileProcessingResult processUrlFile(
      Path localFile,
      String remoteUrl,
      String lastModified,
      long remoteFileSize,
      String originalFileName)
      throws IOException {

    String fileName = originalFileName;

    // Compute SHA-256 on the downloaded file for content-based deduplication
    String checksum = checksumService.computeSha256(localFile);

    // Check if document already exists by remote URL
    Optional<Document> existing = documentRepository.findByFilePath(remoteUrl);
    if (existing.isPresent()) {
      Document existingDoc = existing.get();
      if (checksum.equals(existingDoc.getChecksum())
          && existingDoc.getStatus() == DocumentStatus.INDEXED) {
        log.info("Skipping unchanged URL document (same checksum): {}", fileName);
        metrics.recordSkipped();
        return FileProcessingResult.SKIPPED;
      }
      // Document changed — delete old data
      vectorStore.delete("document_id == '" + existingDoc.getId().toString() + "'");
      documentRepository.delete(existingDoc);
    }

    String contentType = Files.probeContentType(localFile);

    var doc =
        new Document(
            fileName, remoteUrl, contentType, remoteFileSize, DocumentSourceType.HTTP_DIRECTORY);
    doc = documentRepository.save(doc);

    try {
      List<org.springframework.ai.document.Document> parsed =
          documentService.parseDocument(localFile);
      if (parsed.isEmpty()) {
        log.warn("No content extracted from URL document: {}", remoteUrl);
        doc.setStatus(DocumentStatus.FAILED);
        documentRepository.save(doc);
        return FileProcessingResult.PROCESSED;
      }

      List<org.springframework.ai.document.Document> chunks =
          chunkingService.chunkDocuments(fileName, parsed);
      log.debug("URL file {} produced {} chunks", fileName, chunks.size());

      storeChunks(doc, chunks);

      doc.setChunkCount(chunks.size());
      doc.setIndexedAt(Instant.now());
      doc.setChecksum(checksum);
      doc.setLastModifiedRemote(lastModified);
      doc.setStatus(DocumentStatus.INDEXED);
      documentRepository.save(doc);
    } catch (Exception e) {
      doc.setStatus(DocumentStatus.FAILED);
      documentRepository.save(doc);
      metrics.recordFailed();
      throw e;
    }

    metrics.recordProcessed();
    return FileProcessingResult.PROCESSED;
  }

  private void storeChunks(
      Document document, List<org.springframework.ai.document.Document> chunks) {
    Optional<UUID> defaultWorkspaceId = resolveDefaultWorkspaceId();
    List<org.springframework.ai.document.Document> enriched =
        chunks.stream()
            .map(
                chunk -> {
                  int index = chunks.indexOf(chunk);
                  Map<String, Object> metadata = new HashMap<>();
                  metadata.put("document_id", document.getId().toString());
                  metadata.put("chunk_index", index);
                  metadata.put("file_name", document.getFileName());
                  defaultWorkspaceId.ifPresent(id -> metadata.put("workspace_id", id.toString()));
                  return new org.springframework.ai.document.Document(chunk.getText(), metadata);
                })
            .toList();

    vectorStore.add(enriched);
  }

  private Optional<UUID> resolveDefaultWorkspaceId() {
    String defaultName = workspaceProperties.defaultWorkspace().name();
    return workspaceRepository
        .findByNameIgnoreCase(defaultName)
        .map(
            workspace -> {
              log.debug("Assigning default workspace '{}' to chunks", workspace.getName());
              return workspace.getId();
            })
        .or(
            () -> {
              log.warn(
                  "Default workspace '{}' not found — chunks will be stored without workspace_id",
                  defaultName);
              return Optional.empty();
            });
  }
}
