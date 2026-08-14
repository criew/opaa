package io.opaa.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.opaa.api.dto.QueryResponse;
import io.opaa.indexing.*;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.organization.Organization;
import io.opaa.query.QueryService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end integration test using real OpenAI API. Only runs when OPAA_OPENAI_API_KEY environment
 * variable is set.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "OPAA_OPENAI_API_KEY", matches = ".+")
class OpenAiIntegrationTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

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

  // The real seeded ids (Organization.DEFAULT_ID, KnowledgeLibrary.SYSTEM_LIBRARY_ID), not
  // locally duplicated string literals - both are UUID, and both are bound as JDBC parameters
  // against uuid-typed columns (asset_grants.library_id, users.organization_id) via a plain
  // PreparedStatement, which does not auto-cast a text/varchar parameter to uuid the way an
  // inline SQL literal would. A local String constant here surfaced as a BadSqlGrammarException
  // ("operator does not exist: uuid = text") the moment this test actually executed (#309 code
  // review round 4: it never had, because this whole class is gated behind OPAA_OPENAI_API_KEY
  // and was never run after being adapted to the asset-grants model in #305/#309).
  private static final UUID SEEDED_ORGANIZATION_ID = Organization.DEFAULT_ID;
  private static final UUID SYSTEM_LIBRARY_ID = KnowledgeLibrary.SYSTEM_LIBRARY_ID;

  @Autowired private DocumentIndexingService documentIndexingService;
  @Autowired private QueryService queryService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID userId;

  @BeforeEach
  void setUp() throws IOException {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store");
    documentRepository.deleteAll();
    indexingJobRepository.deleteAll();
    jdbcTemplate.update("DELETE FROM asset_grants WHERE library_id = ?", SYSTEM_LIBRARY_ID);
    jdbcTemplate.update("DELETE FROM users WHERE email = 'openai-it@example.com'");
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', 'openai-it@example.com',"
            + " 'OpenAI IT User', now(), 'SYSTEM_ADMIN', ?)",
        userId,
        "openai-it-" + userId,
        SEEDED_ORGANIZATION_ID);
    // Manual indexing (via FileProcessingService) still files documents into the system library
    // until #207 wires connector sources to a chosen library - see the note in
    // docs/features/spaces-and-assets.md. This test's user needs an explicit grant to find them,
    // exactly like every other reader now does (#202: search never bypasses grants, not even for
    // a system admin).
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
            + " subject_user_id, role, created_at, updated_at) VALUES (?, ?, ?, 'USER', ?,"
            + " 'OWNER', now(), now())",
        UUID.randomUUID(),
        SYSTEM_LIBRARY_ID,
        SEEDED_ORGANIZATION_ID,
        userId);
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
    QueryResponse response = queryService.query("What does OPAA stand for?", null, userId);

    assertThat(response.getAnswer()).isNotBlank();
    assertThat(response.getAnswer().toLowerCase()).contains("open project ai assistant");
    assertThat(response.getSources()).isNotEmpty();
    assertThat(response.getSources().getFirst().getFileName()).isEqualTo("opaa-info.md");
    assertThat(response.getSources().getFirst().getRelevanceScore()).isGreaterThan(0.0);
    assertThat(response.getMetadata().getModel()).isNotBlank();
    assertThat(response.getMetadata().getTokenCount()).isGreaterThan(0);
    assertThat(response.getMetadata().getDurationMs()).isGreaterThan(0);
  }
}
