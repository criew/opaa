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
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!mock")
public class DocumentIndexingService {

  private static final Logger log = LoggerFactory.getLogger(DocumentIndexingService.class);

  private final DocumentService documentService;
  private final ChunkingService chunkingService;
  private final IndexingJobService indexingJobService;
  private final DocumentRepository documentRepository;
  private final DocumentChunkRepository documentChunkRepository;
  private final EmbeddingModel embeddingModel;
  private final JdbcTemplate jdbcTemplate;
  private final IndexingProperties properties;

  public DocumentIndexingService(
      DocumentService documentService,
      ChunkingService chunkingService,
      IndexingJobService indexingJobService,
      DocumentRepository documentRepository,
      DocumentChunkRepository documentChunkRepository,
      EmbeddingModel embeddingModel,
      JdbcTemplate jdbcTemplate,
      IndexingProperties properties) {
    this.documentService = documentService;
    this.chunkingService = chunkingService;
    this.indexingJobService = indexingJobService;
    this.documentRepository = documentRepository;
    this.documentChunkRepository = documentChunkRepository;
    this.embeddingModel = embeddingModel;
    this.jdbcTemplate = jdbcTemplate;
    this.properties = properties;
  }

  public IndexingJob triggerIndexing() {
    var job = indexingJobService.startJob();
    int processed = 0;
    int failed = 0;

    try {
      Path documentDir = Path.of(properties.documentPath());
      List<Path> files = documentService.discoverFiles(documentDir);
      log.info("Discovered {} files for indexing in {}", files.size(), documentDir);

      for (Path file : files) {
        try {
          processFile(file);
          processed++;
        } catch (Exception e) {
          log.error("Failed to process file: {}", file, e);
          failed++;
        }
      }

      indexingJobService.completeJob(job.getId(), processed, failed);
    } catch (IOException e) {
      log.error("Failed to discover files", e);
      indexingJobService.failJob(job.getId(), e.getMessage());
    } catch (Exception e) {
      log.error("Indexing failed unexpectedly", e);
      indexingJobService.failJob(job.getId(), e.getMessage());
    }

    return indexingJobService
        .getLatestJob()
        .orElseThrow(() -> new IllegalStateException("Job not found after indexing"));
  }

  @Transactional
  void processFile(Path file) throws IOException {
    String filePath = file.toAbsolutePath().toString();
    String fileName = file.getFileName().toString();
    String contentType = Files.probeContentType(file);
    long fileSize = Files.size(file);

    // Check if document already exists and delete old chunks
    documentRepository
        .findByFilePath(filePath)
        .ifPresent(
            existing -> {
              documentChunkRepository.deleteByDocumentId(existing.getId());
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
      storeChunksWithEmbeddings(doc.getId(), chunks);

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
      UUID documentId, List<org.springframework.ai.document.Document> chunks) {
    // Extract chunk texts for batch embedding
    List<String> texts =
        chunks.stream().map(org.springframework.ai.document.Document::getText).toList();

    // Generate embeddings in batches
    List<float[]> allEmbeddings = generateEmbeddingsInBatches(texts);

    // Store chunks with embeddings
    for (int i = 0; i < chunks.size(); i++) {
      var chunk = new DocumentChunk(documentId, i, texts.get(i));
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
          log.warn(
              "Embedding batch failed (attempt {}/{}), retrying...",
              attempts,
              properties.retryAttempts(),
              e);
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
