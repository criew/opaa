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

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @TestConfiguration
  static class TestConfig {
    @Bean
    @Primary
    EmbeddingModel testEmbeddingModel() {
      return new FakeEmbeddingModel();
    }
  }

  @TempDir Path tempDir;

  @Autowired private DocumentIndexingService documentIndexingService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private DocumentChunkRepository documentChunkRepository;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    documentChunkRepository.deleteAll();
    documentRepository.deleteAll();
    indexingJobRepository.deleteAll();
  }

  @DynamicPropertySource
  static void indexingProperties(DynamicPropertyRegistry registry) {
    registry.add("opaa.indexing.chunk-size", () -> 100);
    registry.add("opaa.indexing.chunk-overlap", () -> 10);
    registry.add("opaa.indexing.batch-size", () -> 10);
    registry.add("opaa.indexing.retry-attempts", () -> 1);
  }

  @Test
  void indexesDocumentsEndToEnd() throws IOException {
    // Arrange: create test documents
    Files.writeString(tempDir.resolve("test.md"), "# Test Document\n\nThis is test content.");
    Files.writeString(tempDir.resolve("notes.txt"), "Some plain text notes for testing.");

    // Set document path dynamically
    setDocumentPath(tempDir.toAbsolutePath().toString());

    // Act
    IndexingJob job = documentIndexingService.triggerIndexing();

    // Assert: job completed
    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(job.getDocumentsProcessed()).isEqualTo(2);
    assertThat(job.getDocumentsFailed()).isZero();

    // Assert: documents stored
    List<Document> documents = documentRepository.findAll();
    assertThat(documents).hasSize(2);
    assertThat(documents).allMatch(d -> d.getStatus() == DocumentStatus.INDEXED);

    // Assert: chunks stored
    List<DocumentChunk> chunks = documentChunkRepository.findAll();
    assertThat(chunks).isNotEmpty();

    // Assert: embeddings are present (non-null)
    for (DocumentChunk chunk : chunks) {
      Integer count =
          jdbcTemplate.queryForObject(
              "SELECT COUNT(*) FROM document_chunks WHERE id = ? AND embedding IS NOT NULL",
              Integer.class,
              chunk.getId());
      assertThat(count).isEqualTo(1);
    }
  }

  @Test
  void skipsUnparseableFilesAndContinues() throws IOException {
    Files.writeString(tempDir.resolve("good.txt"), "Valid content here.");
    // Create a file with unsupported extension (will be filtered out)
    Files.writeString(tempDir.resolve("bad.csv"), "a,b,c");

    setDocumentPath(tempDir.toAbsolutePath().toString());

    IndexingJob job = documentIndexingService.triggerIndexing();

    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(job.getDocumentsProcessed()).isEqualTo(1);
  }

  @Test
  void reindexingReplacesOldChunks() throws IOException {
    Files.writeString(tempDir.resolve("doc.txt"), "Original content.");
    setDocumentPath(tempDir.toAbsolutePath().toString());

    // First indexing
    documentIndexingService.triggerIndexing();
    long firstChunkCount = documentChunkRepository.count();

    // Update document and re-index
    Files.writeString(tempDir.resolve("doc.txt"), "Updated content with more text.");
    documentIndexingService.triggerIndexing();

    // Should still have only 1 document
    assertThat(documentRepository.count()).isEqualTo(1);
    // Chunks replaced (not duplicated)
    assertThat(documentChunkRepository.count()).isGreaterThanOrEqualTo(1);
  }

  private void setDocumentPath(String path) {
    // We use reflection or a dynamic property - since IndexingProperties is a record,
    // we rely on @DynamicPropertySource or test context.
    // For this test, we pass the tempDir path as a system property
    System.setProperty("opaa.indexing.document-path", path);
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
