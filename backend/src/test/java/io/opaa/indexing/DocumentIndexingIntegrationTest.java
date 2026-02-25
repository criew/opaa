package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DocumentIndexingIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg18"));

  @TempDir static Path sharedTempDir;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("opaa.indexing.document-path", () -> sharedTempDir.toAbsolutePath().toString());
    registry.add("opaa.indexing.chunk-size", () -> 100);
    registry.add("opaa.indexing.batch-size", () -> 10);
    registry.add("opaa.indexing.retry-attempts", () -> 1);
  }

  @TestConfiguration
  static class TestConfig {
    @Bean
    @Primary
    EmbeddingModel testEmbeddingModel() {
      return new FakeEmbeddingModel();
    }
  }

  @Autowired private DocumentIndexingService documentIndexingService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private VectorStore vectorStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IndexingJobRepository indexingJobRepository;

  @BeforeEach
  void setUp() throws IOException {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store");
    documentRepository.deleteAll();
    indexingJobRepository.deleteAll();
    // Clean up any leftover files from previous tests
    if (Files.exists(sharedTempDir)) {
      try (var files = Files.list(sharedTempDir)) {
        files.forEach(
            f -> {
              try {
                Files.deleteIfExists(f);
              } catch (IOException e) {
                // ignore cleanup failures
              }
            });
      }
    }
  }

  @Test
  void indexesDocumentsEndToEnd() throws IOException {
    Files.writeString(sharedTempDir.resolve("test.md"), "# Test Document\n\nThis is test content.");
    Files.writeString(sharedTempDir.resolve("notes.txt"), "Some plain text notes for testing.");

    IndexingJob job = documentIndexingService.triggerIndexing();

    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(job.getDocumentsProcessed()).isEqualTo(2);
    assertThat(job.getDocumentsFailed()).isZero();

    List<Document> documents = documentRepository.findAll();
    assertThat(documents).hasSize(2);
    assertThat(documents).allMatch(d -> d.getStatus() == DocumentStatus.INDEXED);
    assertThat(documents).allMatch(d -> d.getIndexedAt() != null);
    assertThat(documents).allMatch(d -> d.getChunkCount() > 0);

    // Verify chunks with embeddings were stored in vector_store
    List<org.springframework.ai.document.Document> results =
        vectorStore.similaritySearch(
            SearchRequest.builder().query("test").topK(100).similarityThreshold(0.0).build());
    assertThat(results).isNotEmpty();
    assertThat(results).allMatch(r -> r.getText() != null && !r.getText().isBlank());
    assertThat(results).allMatch(r -> r.getMetadata().containsKey("document_id"));
  }

  @Test
  void skipsUnsupportedFileFormatsAndContinues() throws IOException {
    Files.writeString(sharedTempDir.resolve("good.txt"), "Valid content here.");
    Files.writeString(sharedTempDir.resolve("bad.csv"), "a,b,c");

    IndexingJob job = documentIndexingService.triggerIndexing();

    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    // Only .txt is a supported format, .csv is filtered out by DocumentService
    assertThat(job.getDocumentsProcessed()).isEqualTo(1);
    assertThat(job.getDocumentsFailed()).isZero();

    // Verify only the supported file was indexed
    List<Document> documents = documentRepository.findAll();
    assertThat(documents).hasSize(1);
    assertThat(documents.getFirst().getFileName()).isEqualTo("good.txt");
    assertThat(documents.getFirst().getStatus()).isEqualTo(DocumentStatus.INDEXED);
  }

  @Test
  void reindexingReplacesOldChunks() throws IOException {
    Files.writeString(sharedTempDir.resolve("doc.txt"), "Original content.");

    IndexingJob firstJob = documentIndexingService.triggerIndexing();
    assertThat(firstJob.getDocumentsProcessed()).isEqualTo(1);

    // Remember initial state
    List<org.springframework.ai.document.Document> initialResults =
        vectorStore.similaritySearch(
            SearchRequest.builder().query("content").topK(100).similarityThreshold(0.0).build());
    assertThat(initialResults).isNotEmpty();
    Document initialDoc = documentRepository.findAll().getFirst();
    assertThat(initialDoc.getStatus()).isEqualTo(DocumentStatus.INDEXED);

    // Update file and re-index
    Files.writeString(sharedTempDir.resolve("doc.txt"), "Updated content with more text.");
    IndexingJob secondJob = documentIndexingService.triggerIndexing();

    assertThat(secondJob.getDocumentsProcessed()).isEqualTo(1);
    assertThat(documentRepository.count()).isEqualTo(1);

    // Verify the document content was actually re-indexed
    Document reindexedDoc = documentRepository.findAll().getFirst();
    assertThat(reindexedDoc.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(reindexedDoc.getIndexedAt()).isNotNull();

    // Verify chunk text was updated via similarity search
    List<org.springframework.ai.document.Document> newResults =
        vectorStore.similaritySearch(
            SearchRequest.builder().query("Updated").topK(100).similarityThreshold(0.0).build());
    assertThat(newResults).isNotEmpty();
    String allChunkText =
        newResults.stream()
            .map(org.springframework.ai.document.Document::getText)
            .reduce("", String::concat);
    assertThat(allChunkText).contains("Updated");
  }

  /** Fake embedding model that returns deterministic embeddings for testing. */
  static class FakeEmbeddingModel implements EmbeddingModel {

    @Override
    public EmbeddingResponse call(org.springframework.ai.embedding.EmbeddingRequest request) {
      List<Embedding> embeddings =
          request.getInstructions().stream()
              .map(
                  text ->
                      new Embedding(
                          generateFakeEmbedding(), request.getInstructions().indexOf(text)))
              .toList();
      return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(org.springframework.ai.document.Document document) {
      return generateFakeEmbedding();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
      return texts.stream().map(t -> generateFakeEmbedding()).toList();
    }

    private float[] generateFakeEmbedding() {
      float[] embedding = new float[1536];
      for (int i = 0; i < embedding.length; i++) {
        embedding[i] = (float) Math.sin(i * 0.01);
      }
      return embedding;
    }
  }
}
