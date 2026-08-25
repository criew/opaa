package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.opaa.FakeEmbeddingModel;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * End-to-end coverage of #886: {@link StaleDocumentCleanupService}, wired into {@link
 * AsyncIndexingExecutor} (FILESYSTEM), removes a document - row and vector store chunks - that
 * vanished from the source, but only once a run finished successfully. Runs against the real
 * Liquibase schema and a real {@code vector_store} table (AGENTS.md "Reproduktionsnachweis"), the
 * same Testcontainers/{@link FakeEmbeddingModel} setup {@link DocumentIndexingIntegrationTest} uses
 * - the whole point of these tests is proving chunks are actually gone from pgvector, not just that
 * a repository method was called.
 */
// Own @DynamicPropertySource (below, indexing-specific paths/chunk sizing) means Spring's context
// cache still keys this to its own context regardless of the shared @OpaaIntegrationTest base -
// documented exception per AGENTS.md.
@OpaaIntegrationTest
class StaleDocumentCleanupIntegrationTest {

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

  @Autowired private DocumentIndexingService documentIndexingService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @MockitoBean private ChatModel chatModel;
  @MockitoBean private ActiveChatModelResolver activeChatModelResolver;

  private UUID userId;
  private UUID targetLibraryId;

  @BeforeEach
  void setUp() throws IOException {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store");
    documentRepository.deleteAll();
    indexingJobRepository.deleteAll();
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

    jdbcTemplate.update(
        "DELETE FROM knowledge_libraries WHERE owner_user_id IN (SELECT id FROM users WHERE"
            + " email = 'stale-cleanup-it@example.com')");
    jdbcTemplate.update("DELETE FROM users WHERE email = 'stale-cleanup-it@example.com'");
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', 'stale-cleanup-it@example.com',"
            + " 'Stale Cleanup IT User', now(), ?, ?)",
        userId,
        "stale-cleanup-it-" + userId,
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);

    KnowledgeLibrary library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Zielbibliothek",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.FILESYSTEM,
                sharedTempDir.toAbsolutePath().toString(),
                null,
                null,
                null,
                false));
    targetLibraryId = library.getId();
    grantOwner(targetLibraryId, userId);
  }

  private void grantOwner(UUID libraryId, UUID granteeId) {
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
            + " subject_user_id, role, created_at, updated_at) VALUES (?, ?, ?, 'USER', ?,"
            + " 'OWNER', now(), now())",
        UUID.randomUUID(),
        libraryId,
        Organization.DEFAULT_ID,
        granteeId);
  }

  private IndexingJob triggerIndexing() {
    return documentIndexingService.triggerIndexing(
        targetLibraryId,
        CurrentUser.of(userId, Organization.DEFAULT_ID, SystemRole.SYSTEM_ADMIN, null));
  }

  private void awaitJobCompletion(IndexingJob job) {
    await()
        .atMost(30, TimeUnit.SECONDS)
        .until(
            () -> {
              var latestJob = indexingJobRepository.findById(job.getId()).orElseThrow();
              return latestJob.getStatus() != JobStatus.RUNNING;
            });
  }

  private long chunkCountFor(UUID documentId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = ?",
            Long.class,
            documentId.toString());
    return count == null ? 0 : count;
  }

  @Test
  void aDocumentWhoseFileVanishedIsRemovedWithItsChunksAfterASuccessfulRun() throws IOException {
    Files.writeString(sharedTempDir.resolve("keep.txt"), "This file stays.");
    Files.writeString(sharedTempDir.resolve("vanishing.txt"), "This file will disappear.");

    IndexingJob firstJob = triggerIndexing();
    awaitJobCompletion(firstJob);
    assertThat(indexingJobRepository.findById(firstJob.getId()).orElseThrow().getStatus())
        .isEqualTo(JobStatus.COMPLETED);
    assertThat(documentRepository.findByLibraryId(targetLibraryId)).hasSize(2);

    Document vanishingDoc =
        documentRepository.findByLibraryId(targetLibraryId).stream()
            .filter(d -> d.getFileName().equals("vanishing.txt"))
            .findFirst()
            .orElseThrow();
    UUID vanishingDocId = vanishingDoc.getId();
    assertThat(chunkCountFor(vanishingDocId)).isPositive();

    Document keptDoc =
        documentRepository.findByLibraryId(targetLibraryId).stream()
            .filter(d -> d.getFileName().equals("keep.txt"))
            .findFirst()
            .orElseThrow();

    Files.delete(sharedTempDir.resolve("vanishing.txt"));

    IndexingJob secondJob = triggerIndexing();
    awaitJobCompletion(secondJob);
    assertThat(indexingJobRepository.findById(secondJob.getId()).orElseThrow().getStatus())
        .isEqualTo(JobStatus.COMPLETED);

    assertThat(documentRepository.findById(vanishingDocId))
        .as("the document whose file vanished from the source must be removed")
        .isEmpty();
    assertThat(chunkCountFor(vanishingDocId))
        .as("its chunks must be removed from the vector store too")
        .isZero();

    List<Document> remaining = documentRepository.findByLibraryId(targetLibraryId);
    assertThat(remaining).hasSize(1);
    assertThat(remaining.getFirst().getId()).isEqualTo(keptDoc.getId());
  }

  @Test
  void aFailedRunNeverDeletesADocumentEvenIfItsFileVanishedFromTheSource() throws IOException {
    Files.writeString(sharedTempDir.resolve("survivor.txt"), "Must survive a failed run.");

    IndexingJob firstJob = triggerIndexing();
    awaitJobCompletion(firstJob);
    assertThat(indexingJobRepository.findById(firstJob.getId()).orElseThrow().getStatus())
        .isEqualTo(JobStatus.COMPLETED);
    Document survivor =
        documentRepository.findByLibraryId(targetLibraryId).stream().findFirst().orElseThrow();

    // The file "vanishes" (deleted, exactly like the successful-cleanup test above), but this
    // time the library's own sourcePath is also narrowed outside the configured allowlist before
    // the next run - AsyncIndexingExecutor rejects it before ever calling discoverFiles, and the
    // job fails (ADR-0018 Entscheidung 6, mirrors
    // triggerIndexingFailsTheJobWhenSourcePathIsOutsideTheConfiguredAllowlist in
    // DocumentIndexingIntegrationTest). A capped/failed run's currentFilePaths would be incomplete
    // - cleanupVanished must never run at all here.
    Files.delete(sharedTempDir.resolve("survivor.txt"));
    Path outsideAllowlist = sharedTempDir.resolveSibling("opaa-886-outside-allowlist");
    jdbcTemplate.update(
        "UPDATE knowledge_libraries SET source_path = ? WHERE id = ?",
        outsideAllowlist.toAbsolutePath().toString(),
        targetLibraryId);

    IndexingJob secondJob = triggerIndexing();
    awaitJobCompletion(secondJob);

    var failedJob = indexingJobRepository.findById(secondJob.getId()).orElseThrow();
    assertThat(failedJob.getStatus()).isEqualTo(JobStatus.FAILED);

    assertThat(documentRepository.findById(survivor.getId()))
        .as("a failed run must not delete a document, even though its file is gone too")
        .isPresent();
    assertThat(chunkCountFor(survivor.getId())).isPositive();
  }
}
