package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.FakeEmbeddingModel;
import io.opaa.auth.SystemRole;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryVisibility;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Reproduces #877 (Epic #826, Befund B6) against the real Liquibase schema and the real, unmocked
 * {@link DocumentRepository}: two libraries independently indexing the same filesystem path. Before
 * the fix, {@code FileProcessingService#processFile}'s dedup lookup ({@code
 * documentRepository.findByFilePath}, scoped only to {@code file_path}) found the first library's
 * document when the second library indexed the same path, and - because the target library differed
 * - treated it as a "move": deleted the first library's chunks and row outright instead of creating
 * an independent document for the second library. This test proves the fixed behaviour (each
 * library keeps its own, independently indexed document) and, run against the pre-fix code with
 * {@code uk_documents_library_path} absent, documents the exact failure this closes (see the PR
 * description for the red run's assertion failure).
 */
@OpaaIntegrationTest
class FileProcessingServiceLibraryScopingIntegrationTest {

  @TempDir static Path sharedTempDir;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("opaa.indexing.document-path", () -> sharedTempDir.toAbsolutePath().toString());
    registry.add(
        "opaa.indexing.filesystem-allowlist", () -> sharedTempDir.toAbsolutePath().toString());
    registry.add("opaa.indexing.chunk-size", () -> 100);
    registry.add("opaa.indexing.chunk-overlap", () -> 10);
    registry.add("opaa.indexing.batch-size", () -> 10);
  }

  @TestConfiguration
  static class TestConfig {
    @Bean
    @Primary
    EmbeddingModel testEmbeddingModel() {
      return new FakeEmbeddingModel();
    }
  }

  @Autowired private FileProcessingService fileProcessingService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private KnowledgeLibraryRepository libraryRepository;

  private UUID userId;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store");
    documentRepository.deleteAll();

    jdbcTemplate.update(
        "DELETE FROM knowledge_libraries WHERE owner_user_id IN (SELECT id FROM users WHERE"
            + " email = 'library-scoping-it@example.com')");
    jdbcTemplate.update("DELETE FROM users WHERE email = 'library-scoping-it@example.com'");
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', 'library-scoping-it@example.com',"
            + " 'Library Scoping IT User', now(), ?, ?)",
        userId,
        "library-scoping-it-" + userId,
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
  }

  @Test
  void twoLibrariesIndexingTheSameSourcePathEachKeepTheirOwnIndependentDocument()
      throws IOException {
    Path file = sharedTempDir.resolve("shared-source.txt");
    Files.writeString(file, "content indexed into two different libraries");

    KnowledgeLibrary libraryA = newLibrary("Bibliothek A");
    KnowledgeLibrary libraryB = newLibrary("Bibliothek B");

    FileProcessingResult resultA = fileProcessingService.processFile(file, libraryA);
    assertThat(resultA).isEqualTo(FileProcessingResult.PROCESSED);

    Document docBeforeSecondRun =
        documentRepository
            .findByLibraryIdAndFilePath(libraryA.getId(), file.toAbsolutePath().toString())
            .orElseThrow();
    assertThat(docBeforeSecondRun.getStatus()).isEqualTo(DocumentStatus.INDEXED);

    // The behaviour under test: indexing the identical path into a second library must not touch
    // library A's document or its chunks - it creates library B's own, independent document.
    FileProcessingResult resultB = fileProcessingService.processFile(file, libraryB);
    assertThat(resultB).isEqualTo(FileProcessingResult.PROCESSED);

    assertThat(
            documentRepository.findByLibraryIdAndFilePath(
                libraryA.getId(), file.toAbsolutePath().toString()))
        .as("library A's document must still exist after library B indexes the same path")
        .isPresent();
    assertThat(
            documentRepository.findByLibraryIdAndFilePath(
                libraryB.getId(), file.toAbsolutePath().toString()))
        .as("library B must have its own document for the same path")
        .isPresent();
    assertThat(documentRepository.count())
        .as("both libraries must keep their own document - neither library steals the other's")
        .isEqualTo(2);

    Long chunkCountForA =
        countVectorStoreRowsForDocument(
            documentRepository
                .findByLibraryIdAndFilePath(libraryA.getId(), file.toAbsolutePath().toString())
                .orElseThrow()
                .getId());
    assertThat(chunkCountForA)
        .as("library A's chunks must survive library B's independent indexing run")
        .isPositive();
  }

  private KnowledgeLibrary newLibrary(String name) {
    return libraryRepository.save(
        KnowledgeLibrary.ownedByUser(
            Organization.DEFAULT_ID, name, null, userId, LibraryVisibility.PRIVATE, false));
  }

  private Long countVectorStoreRowsForDocument(UUID documentId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = ?",
        Long.class,
        documentId.toString());
  }
}
