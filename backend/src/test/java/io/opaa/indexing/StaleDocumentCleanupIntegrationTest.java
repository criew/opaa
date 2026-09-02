package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.indexing.source.filesystem.AsyncIndexingExecutor;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIndexingIntegrationTest;
import io.opaa.test.OpaaIndexingTestDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end coverage of #886: {@link StaleDocumentCleanupService}, wired into {@link
 * AsyncIndexingExecutor} (FILESYSTEM), removes a document - row and vector store chunks - that
 * vanished from the source, but only once a run finished successfully. Runs against the real
 * Liquibase schema and a real {@code vector_store} table (AGENTS.md "Reproduktionsnachweis"), the
 * same Testcontainers/fake-embedding-model setup every {@link
 * io.opaa.test.OpaaIndexingIntegrationTest} class shares - the whole point of these tests is
 * proving chunks are actually gone from pgvector, not just that a repository method was called.
 */
@OpaaIndexingIntegrationTest
class StaleDocumentCleanupIntegrationTest {

  private static final Path classTempDir =
      OpaaIndexingTestDirectory.subdirectory("stale-document-cleanup");

  @Autowired private DocumentIndexingService documentIndexingService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private IndexingRunEventRepository indexingRunEventRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;

  private UUID userId;
  private UUID targetLibraryId;

  @BeforeEach
  void setUp() throws IOException {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text, chunk_full_text_skip");
    documentRepository.deleteAll();
    indexingJobRepository.deleteAll();
    if (Files.exists(classTempDir)) {
      try (var files = Files.list(classTempDir)) {
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
                classTempDir.toAbsolutePath().toString(),
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

  private void insertDocument(UUID libraryId, String fileName, DocumentSourceType sourceType) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO documents (id, file_name, file_path, content_type, file_size, chunk_count,"
            + " indexed_at, checksum, status, source_type, library_id, organization_id,"
            + " created_at) VALUES (?, ?, ?, 'text/plain', 1, 0, now(), ?, 'INDEXED', ?, ?, ?,"
            + " now())",
        id,
        fileName,
        "irrelevant-path-for-" + fileName,
        "checksum-" + id,
        sourceType.name(),
        libraryId,
        Organization.DEFAULT_ID);
  }

  @Test
  void aDocumentWhoseFileVanishedIsRemovedWithItsChunksAfterASuccessfulRun() throws IOException {
    Files.writeString(classTempDir.resolve("keep.txt"), "This file stays.");
    Files.writeString(classTempDir.resolve("vanishing.txt"), "This file will disappear.");
    // #886 review, ADR-0017 core rule: cleanup is scoped to (library, sourceType) - a document of
    // a different sourceType in the very same (nominally FILESYSTEM) library must survive
    // regardless of what the FILESYSTEM cleanup below does. Inserted directly, bypassing the
    // "one source type per library" rule ADR-0018 normally enforces at creation time, to prove the
    // cleanup query itself never crosses sourceType even if such a row exists.
    insertDocument(targetLibraryId, "upload.txt", DocumentSourceType.UPLOAD);
    insertDocument(targetLibraryId, "feed-entry.html", DocumentSourceType.RSS_FEED);

    IndexingJob firstJob = triggerIndexing();
    awaitJobCompletion(firstJob);
    assertThat(indexingJobRepository.findById(firstJob.getId()).orElseThrow().getStatus())
        .isEqualTo(JobStatus.COMPLETED);
    assertThat(documentRepository.findByLibraryId(targetLibraryId)).hasSize(4);

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
    UUID uploadDocId =
        documentRepository.findByLibraryId(targetLibraryId).stream()
            .filter(d -> d.getFileName().equals("upload.txt"))
            .findFirst()
            .orElseThrow()
            .getId();
    UUID rssDocId =
        documentRepository.findByLibraryId(targetLibraryId).stream()
            .filter(d -> d.getFileName().equals("feed-entry.html"))
            .findFirst()
            .orElseThrow()
            .getId();

    Files.delete(classTempDir.resolve("vanishing.txt"));

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
    assertThat(documentRepository.findById(uploadDocId))
        .as("an UPLOAD document in the same library is a different sourceType and must survive")
        .isPresent();
    assertThat(documentRepository.findById(rssDocId))
        .as("an RSS_FEED document in the same library is a different sourceType and must survive")
        .isPresent();

    List<Document> remaining = documentRepository.findByLibraryId(targetLibraryId);
    assertThat(remaining)
        .extracting(Document::getId)
        .containsExactlyInAnyOrder(keptDoc.getId(), uploadDocId, rssDocId);

    // #886 review (finding 5): the run's own protocol names what was removed and not just how many.
    List<IndexingRunEvent> events =
        indexingRunEventRepository.findByJobIdOrderByCreatedAtAsc(secondJob.getId());
    assertThat(events)
        .anyMatch(
            e ->
                e.getCategory() == IndexingEventCategory.REMOVED
                    && vanishingDoc.getFilePath().equals(e.getReference()));
  }

  @Test
  void aFailedRunNeverDeletesADocumentEvenIfItsFileVanishedFromTheSource() throws IOException {
    // Sharpened per #886 review: the failure must come from within discoverFiles itself (the
    // production code path that can genuinely race a real source, e.g. an unmounted network
    // share), not from the allowlist pre-check that runs before discoverFiles is ever called -
    // library.sourcePath points at its own dedicated subdirectory of classTempDir (still inside
    // the configured allowlist) so only that subdirectory - never classTempDir itself - is
    // removed between the two runs.
    Path librarySourceDir = classTempDir.resolve("failed-run-source");
    Files.createDirectory(librarySourceDir);
    jdbcTemplate.update(
        "UPDATE knowledge_libraries SET source_path = ? WHERE id = ?",
        librarySourceDir.toAbsolutePath().toString(),
        targetLibraryId);
    Files.writeString(librarySourceDir.resolve("survivor.txt"), "Must survive a failed run.");

    IndexingJob firstJob = triggerIndexing();
    awaitJobCompletion(firstJob);
    assertThat(indexingJobRepository.findById(firstJob.getId()).orElseThrow().getStatus())
        .isEqualTo(JobStatus.COMPLETED);
    Document survivor =
        documentRepository.findByLibraryId(targetLibraryId).stream().findFirst().orElseThrow();

    // The source directory itself disappears entirely (not just the file in it) - the next run's
    // own DocumentService#discoverFiles throws IOException (#886 fix), caught by
    // AsyncIndexingExecutor's outer catch, which fails the job before cleanupVanished (or even
    // pruneOrphanedFolders) is ever reached.
    Files.delete(librarySourceDir.resolve("survivor.txt"));
    Files.delete(librarySourceDir);

    IndexingJob secondJob = triggerIndexing();
    awaitJobCompletion(secondJob);

    var failedJob = indexingJobRepository.findById(secondJob.getId()).orElseThrow();
    assertThat(failedJob.getStatus()).isEqualTo(JobStatus.FAILED);

    assertThat(documentRepository.findById(survivor.getId()))
        .as("a failed run must not delete a document, even though its file is gone too")
        .isPresent();
    assertThat(chunkCountFor(survivor.getId())).isPositive();
  }

  @Test
  void aSuccessfulRunOverAGenuinelyEmptyDirectoryNeverDeletesTheExistingBestand()
      throws IOException {
    // #886 review, finding 1: the directory itself still exists (discoverFiles succeeds, the job
    // COMPLETEs normally) but every file inside it is gone - indistinguishable here from an
    // unreachable/misconfigured source (an empty maintenance mount, a listing OPAA read before the
    // real content was synced). StaleDocumentCleanupService#cleanupVanished's own empty-set guard
    // must still refuse to delete the library's entire previously indexed bestand on that single
    // signal alone.
    Files.writeString(classTempDir.resolve("only-file.txt"), "The only file, for now.");

    IndexingJob firstJob = triggerIndexing();
    awaitJobCompletion(firstJob);
    assertThat(indexingJobRepository.findById(firstJob.getId()).orElseThrow().getStatus())
        .isEqualTo(JobStatus.COMPLETED);
    Document onlyDoc =
        documentRepository.findByLibraryId(targetLibraryId).stream().findFirst().orElseThrow();

    Files.delete(classTempDir.resolve("only-file.txt"));

    IndexingJob secondJob = triggerIndexing();
    awaitJobCompletion(secondJob);
    assertThat(indexingJobRepository.findById(secondJob.getId()).orElseThrow().getStatus())
        .isEqualTo(JobStatus.COMPLETED);

    assertThat(documentRepository.findById(onlyDoc.getId()))
        .as("an empty (but successful) run must not delete every previously indexed document")
        .isPresent();
    assertThat(chunkCountFor(onlyDoc.getId())).isPositive();
  }
}
