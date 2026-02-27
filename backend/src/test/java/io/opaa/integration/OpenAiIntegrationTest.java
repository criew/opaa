package io.opaa.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.opaa.api.dto.QueryResponse;
import io.opaa.indexing.*;
import io.opaa.query.QueryService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end integration test using real OpenAI API. Only runs when OPAA_OPENAI_API_KEY environment
 * variable is set.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "OPAA_OPENAI_API_KEY", matches = ".+")
class OpenAiIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg18"));

  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("opaa.indexing.document-path", () -> tempDir.toAbsolutePath().toString());
    registry.add("opaa.indexing.chunk-size", () -> 200);
    registry.add("opaa.indexing.batch-size", () -> 10);
    registry.add("opaa.indexing.retry-attempts", () -> 1);
  }

  @Autowired private DocumentIndexingService documentIndexingService;
  @Autowired private QueryService queryService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() throws IOException {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store");
    documentRepository.deleteAll();
    indexingJobRepository.deleteAll();
    if (Files.exists(tempDir)) {
      try (var files = Files.list(tempDir)) {
        files.forEach(
            f -> {
              try {
                Files.deleteIfExists(f);
              } catch (IOException e) {
                // ignore
              }
            });
      }
    }
  }

  @Test
  void indexAndQueryWithRealOpenAi() throws IOException {
    // Index a test document
    Files.writeString(
        tempDir.resolve("opaa-info.md"),
        """
        # OPAA Project

        OPAA stands for Open Project AI Assistant.
        It is an open-source project that provides AI-powered document search
        and question answering using Retrieval-Augmented Generation (RAG).
        The backend is built with Java and Spring Boot.
        The frontend uses React and Material UI.
        """);

    IndexingJob job = documentIndexingService.triggerIndexing();
    assumeTrue(
        job.getDocumentsProcessed() > 0,
        "Skipping: OpenAI API returned an error (quota exceeded or rate limited)."
            + " Ensure the API key has sufficient credits.");
    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(job.getDocumentsProcessed()).isEqualTo(1);

    // Query with a question about the indexed document
    QueryResponse response = queryService.query("What does OPAA stand for?", null);

    assertThat(response.answer()).isNotBlank();
    assertThat(response.answer().toLowerCase()).contains("open project ai assistant");
    assertThat(response.sources()).isNotEmpty();
    assertThat(response.sources().getFirst().fileName()).isEqualTo("opaa-info.md");
    assertThat(response.sources().getFirst().relevanceScore()).isGreaterThan(0.0);
    assertThat(response.metadata().model()).isNotBlank();
    assertThat(response.metadata().tokenCount()).isGreaterThan(0);
    assertThat(response.metadata().durationMs()).isGreaterThan(0);
  }
}
