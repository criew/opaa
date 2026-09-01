package io.opaa.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.opaa.TestcontainersConfiguration;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.indexing.*;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.query.QueryResult;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration test using real OpenAI API. Only runs when OPAA_OPENAI_API_KEY environment
 * variable is set.
 *
 * <p>Issue #843 inventory: deliberately not on @OpaaIntegrationTest - runs under the separate
 * {@code openAiIntegrationTest} Gradle task (AGENTS.md), never alongside the shared-context group
 * in {@code ./gradlew test}, so there is no context to share.
 */
// Own context (gradle task never runs alongside the shared groups), shared
// TestcontainersConfiguration container config.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "OPAA_OPENAI_API_KEY", matches = ".+")
class OpenAiIntegrationTest {

  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    // #484: overrides the dev profile's /data,/tmp default so this suite's own @TempDir stays
    // inside the allowlist.
    registry.add("opaa.indexing.filesystem-allowlist", () -> tempDir.toAbsolutePath().toString());
    registry.add("opaa.indexing.chunk-size", () -> 200);
    registry.add("opaa.indexing.batch-size", () -> 10);
  }

  // The real seeded organization id (Organization.DEFAULT_ID), not a locally duplicated string
  // literal - it is bound as a JDBC parameter against a uuid-typed column (users.organization_id)
  // via a plain PreparedStatement, which does not auto-cast a text/varchar parameter to uuid the
  // way an inline SQL literal would. A local String constant here surfaced as a
  // BadSqlGrammarException ("operator does not exist: uuid = text") the moment this test actually
  // executed (#309 code review round 4: it never had, because this whole class is gated behind
  // OPAA_OPENAI_API_KEY and was never run after being adapted to the asset-grants model in
  // #305/#309).
  private static final UUID SEEDED_ORGANIZATION_ID = Organization.DEFAULT_ID;

  @Autowired private DocumentIndexingService documentIndexingService;
  @Autowired private QueryService queryService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID userId;
  private UUID targetLibraryId;

  @BeforeEach
  void setUp() throws IOException {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text");
    documentRepository.deleteAll();
    indexingJobRepository.deleteAll();
    // #478: the trigger endpoint/service reads type and configuration off the library itself, and
    // the seeded system library is DocumentSourceType.UPLOAD, which triggerIndexing now rejects
    // with 409 (no run type). This test needs a document to actually be found and indexed, so it
    // creates its own FILESYSTEM library pointed at tempDir instead of reusing the system library.
    jdbcTemplate.update(
        "DELETE FROM knowledge_libraries WHERE owner_user_id IN (SELECT id FROM users WHERE"
            + " email = 'openai-it@example.com')");
    jdbcTemplate.update("DELETE FROM users WHERE email = 'openai-it@example.com'");
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', 'openai-it@example.com',"
            + " 'OpenAI IT User', now(), 'SYSTEM_ADMIN', ?)",
        userId,
        "openai-it-" + userId,
        SEEDED_ORGANIZATION_ID);
    KnowledgeLibrary library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                SEEDED_ORGANIZATION_ID,
                "OpenAI-IT-Bibliothek",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.FILESYSTEM,
                tempDir.toAbsolutePath().toString(),
                null,
                null,
                null,
                false));
    targetLibraryId = library.getId();
    // #419: an indexing run needs a caller who holds at least EDITOR on the target library.
    // Reading it back via query still needs a grant too, exactly like every other reader (#202:
    // search never bypasses grants, not even for a system admin).
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
            + " subject_user_id, role, created_at, updated_at) VALUES (?, ?, ?, 'USER', ?,"
            + " 'OWNER', now(), now())",
        UUID.randomUUID(),
        targetLibraryId,
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

    IndexingJob job =
        documentIndexingService.triggerIndexing(
            targetLibraryId,
            CurrentUser.of(userId, SEEDED_ORGANIZATION_ID, SystemRole.SYSTEM_ADMIN, null));
    assumeTrue(
        job.getDocumentsProcessed() > 0,
        "Skipping: OpenAI API returned an error (quota exceeded or rate limited)."
            + " Ensure the API key has sufficient credits.");
    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(job.getDocumentsProcessed()).isEqualTo(1);

    // Query with a question about the indexed document
    QueryResult response =
        queryService.query(
            "What does OPAA stand for?",
            null,
            CurrentUser.of(userId, SEEDED_ORGANIZATION_ID, SystemRole.SYSTEM_ADMIN, null),
            true,
            java.util.List.of());

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
