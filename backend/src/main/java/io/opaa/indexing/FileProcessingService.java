package io.opaa.indexing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class FileProcessingService {

  private static final Logger log = LoggerFactory.getLogger(FileProcessingService.class);

  private final DocumentService documentService;
  private final ChunkingService chunkingService;
  private final DocumentRepository documentRepository;
  private final DocumentChunkRepository documentChunkRepository;
  private final EmbeddingModel embeddingModel;
  private final JdbcTemplate jdbcTemplate;
  private final IndexingProperties properties;

  public FileProcessingService(
      DocumentService documentService,
      ChunkingService chunkingService,
      DocumentRepository documentRepository,
      DocumentChunkRepository documentChunkRepository,
      EmbeddingModel embeddingModel,
      JdbcTemplate jdbcTemplate,
      IndexingProperties properties) {
    this.documentService = documentService;
    this.chunkingService = chunkingService;
    this.documentRepository = documentRepository;
    this.documentChunkRepository = documentChunkRepository;
    this.embeddingModel = embeddingModel;
    this.jdbcTemplate = jdbcTemplate;
    this.properties = properties;
  }

  @Transactional
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
              documentChunkRepository.deleteByDocument(existing);
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

      // Generate embeddings and store chunks
      storeChunksWithEmbeddings(doc, chunks);

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

  private void storeChunksWithEmbeddings(
      Document document, List<org.springframework.ai.document.Document> chunks) {
    // Extract chunk texts for batch embedding
    List<String> texts =
        chunks.stream().map(org.springframework.ai.document.Document::getText).toList();

    // Generate embeddings in batches
    List<float[]> allEmbeddings = generateEmbeddingsInBatches(texts);

    // Store chunks with embeddings
    for (int i = 0; i < chunks.size(); i++) {
      var chunk = new DocumentChunk(document, i, texts.get(i));
      chunk = documentChunkRepository.save(chunk);

      // Store embedding via native SQL (pgvector type)
      float[] embedding = allEmbeddings.get(i);
      storeEmbedding(chunk.getId(), embedding);
    }
  }

  private List<float[]> generateEmbeddingsInBatches(List<String> texts) {
    List<float[]> allEmbeddings = new ArrayList<>();
    int batchSize = properties.batchSize();

    for (int i = 0; i < texts.size(); i += batchSize) {
      int end = Math.min(i + batchSize, texts.size());
      List<String> batch = texts.subList(i, end);

      int attempts = 0;
      while (true) {
        try {
          var response = embeddingModel.embed(batch);
          for (var embedding : response) {
            allEmbeddings.add(embedding);
          }
          break;
        } catch (Exception e) {
          attempts++;
          if (attempts >= properties.retryAttempts()) {
            throw new RuntimeException(
                "Embedding generation failed after " + attempts + " attempts", e);
          }
          long backoffMs = (long) Math.pow(2, attempts) * 1000L;
          log.warn(
              "Embedding batch failed (attempt {}/{}), retrying in {}ms...",
              attempts,
              properties.retryAttempts(),
              backoffMs,
              e);
          try {
            Thread.sleep(backoffMs);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during retry backoff", ie);
          }
        }
      }
    }

    return allEmbeddings;
  }

  private void storeEmbedding(UUID chunkId, float[] embedding) {
    String vectorStr = floatArrayToVectorString(embedding);
    jdbcTemplate.update(
        "UPDATE document_chunks SET embedding = ?::vector WHERE id = ?", vectorStr, chunkId);
  }

  static String floatArrayToVectorString(float[] embedding) {
    var sb = new StringBuilder("[");
    for (int i = 0; i < embedding.length; i++) {
      if (i > 0) sb.append(",");
      sb.append(embedding[i]);
    }
    sb.append("]");
    return sb.toString();
  }
}
