package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryFolder;
import io.opaa.library.LibraryFolderRepository;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIndexingIntegrationTest;
import io.opaa.test.OpaaIndexingTestDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end coverage of #824 (Epic #520 Phase 4, ADR-0020): a FILESYSTEM library's real directory
 * structure mirrored into {@code library_folders} by {@link AsyncIndexingExecutor}/{@link
 * io.opaa.library.LibraryFolderService#materializeFolderPath}, kept in sync by {@link
 * io.opaa.library.LibraryFolderService#pruneOrphanedFolders}. Runs against the real Liquibase
 * schema (AGENTS.md "Reproduktionsnachweis" - {@code fk_documents_folder}/{@code
 * fk_library_folders_parent} only exist there, not under {@code ddl-auto=create-drop}), the same
 * Testcontainers/fake-embedding-model setup every {@link io.opaa.test.OpaaIndexingIntegrationTest}
 * class shares.
 */
@OpaaIndexingIntegrationTest
class FilesystemFolderMappingIntegrationTest {

  private static final Path classTempDir =
      OpaaIndexingTestDirectory.subdirectory("filesystem-folder-mapping");

  @Autowired private DocumentIndexingService documentIndexingService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private LibraryFolderRepository folderRepository;
  @Autowired private ChecksumService checksumService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;

  private UUID userId;
  private UUID targetLibraryId;

  @BeforeEach
  void setUp() throws IOException {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text");
    documentRepository.deleteAll();
    indexingJobRepository.deleteAll();
    jdbcTemplate.update("DELETE FROM library_folders");
    if (Files.exists(classTempDir)) {
      try (var files = Files.walk(classTempDir)) {
        files
            .sorted((a, b) -> b.getNameCount() - a.getNameCount())
            .filter(p -> !p.equals(classTempDir))
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (IOException e) {
                    // ignore cleanup failures
                  }
                });
      }
    }

    jdbcTemplate.update(
        "DELETE FROM knowledge_libraries WHERE owner_user_id IN (SELECT id FROM users WHERE"
            + " email = 'folder-mapping-it@example.com')");
    jdbcTemplate.update("DELETE FROM users WHERE email = 'folder-mapping-it@example.com'");
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', 'folder-mapping-it@example.com',"
            + " 'Folder Mapping IT User', now(), ?, ?)",
        userId,
        "folder-mapping-it-" + userId,
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

  private Optional<LibraryFolder> findFolder(UUID parentFolderId, String name) {
    return folderRepository.findByLibraryId(targetLibraryId).stream()
        .filter(f -> java.util.Objects.equals(f.getParentFolderId(), parentFolderId))
        .filter(f -> f.getName().equals(name))
        .findFirst();
  }

  @Test
  void nestedDirectoryStructureIsMirroredAsFolders() throws IOException {
    Files.createDirectories(classTempDir.resolve("Rechtsquellen/2026"));
    Files.writeString(classTempDir.resolve("top.txt"), "Wurzeldokument.");
    Files.writeString(
        classTempDir.resolve("Rechtsquellen/2026/januar.txt"), "Rechtsquelle Januar 2026.");

    awaitJobCompletion(triggerIndexing());

    LibraryFolder rechtsquellen = findFolder(null, "Rechtsquellen").orElseThrow();
    LibraryFolder jahr2026 = findFolder(rechtsquellen.getId(), "2026").orElseThrow();

    Document topDoc =
        documentRepository.findAll().stream()
            .filter(d -> d.getFileName().equals("top.txt"))
            .findFirst()
            .orElseThrow();
    Document januarDoc =
        documentRepository.findAll().stream()
            .filter(d -> d.getFileName().equals("januar.txt"))
            .findFirst()
            .orElseThrow();

    assertThat(topDoc.getFolderId()).isNull();
    assertThat(januarDoc.getFolderId()).isEqualTo(jahr2026.getId());
  }

  @Test
  void repeatedRunsAreIdempotentAndDoNotDuplicateFolders() throws IOException {
    Files.createDirectories(classTempDir.resolve("Archiv"));
    Files.writeString(classTempDir.resolve("Archiv/protokoll.txt"), "Protokoll.");

    awaitJobCompletion(triggerIndexing());
    UUID firstFolderId = findFolder(null, "Archiv").orElseThrow().getId();

    awaitJobCompletion(triggerIndexing());

    List<LibraryFolder> archivFolders =
        folderRepository.findByLibraryId(targetLibraryId).stream()
            .filter(f -> f.getName().equals("Archiv"))
            .toList();
    assertThat(archivFolders).hasSize(1);
    assertThat(archivFolders.getFirst().getId()).isEqualTo(firstFolderId);
  }

  @Test
  void backfillsFolderIdOnADocumentIndexedBeforeFolderMappingExisted() throws IOException {
    // Simulates a document row created before #824: its file already sits under a subdirectory,
    // but folder_id is still NULL (the state every pre-#824 document is in). The next run must
    // not touch its content (same checksum, still INDEXED - a real re-index would be a regression
    // here) but must backfill folder_id, per docs/features/knowledge-sources.md's "Ordner in
    // FILESYSTEM-Bibliotheken".
    Files.createDirectories(classTempDir.resolve("Archiv/2025"));
    Path file = classTempDir.resolve("Archiv/2025/protokoll.txt");
    Files.writeString(file, "Protokoll aus 2025.");
    String checksum = checksumService.computeSha256(file);

    UUID legacyDocumentId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO documents (id, file_name, file_path, content_type, file_size, chunk_count,"
            + " indexed_at, checksum, status, source_type, library_id, organization_id,"
            + " created_at, folder_id) VALUES (?, ?, ?, 'text/plain', ?, 1, now(), ?, 'INDEXED',"
            + " 'FILESYSTEM', ?, ?, now(), NULL)",
        legacyDocumentId,
        "protokoll.txt",
        file.toAbsolutePath().toString(),
        Files.size(file),
        checksum,
        targetLibraryId,
        Organization.DEFAULT_ID);

    IndexingJob job = triggerIndexing();
    awaitJobCompletion(job);

    var completedJob = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(completedJob.getDocumentsSkipped()).isEqualTo(1);
    assertThat(completedJob.getDocumentsProcessed()).isZero();

    Document backfilled = documentRepository.findById(legacyDocumentId).orElseThrow();
    assertThat(backfilled.getFolderId()).isNotNull();
    LibraryFolder archiv = findFolder(null, "Archiv").orElseThrow();
    LibraryFolder jahr2025 = findFolder(archiv.getId(), "2025").orElseThrow();
    assertThat(backfilled.getFolderId()).isEqualTo(jahr2025.getId());
    // The row itself was never re-indexed - same checksum, same indexed_at-independent identity.
    assertThat(backfilled.getChecksum()).isEqualTo(checksum);
  }

  @Test
  void removesAnOrphanedEmptyFolderChainOnceItsDirectoriesAndDocumentAreAllGone()
      throws IOException {
    // #824 review, Befund 3: a two-level chain (Temp/2025/datei.txt), not a single folder - the
    // mock-only unit coverage (LibraryFolderServiceTest#
    // pruneOrphanedFoldersRemovesAnOrphanedParentOnlyAfterItsOwnEmptyOrphanedChild) proves the
    // leaf-first *order* pruneRecursive walks in, but only the real Liquibase schema can prove
    // Hibernate actually flushes those deletes in that order within one transaction - a
    // parent-before-child flush would trip fk_library_folders_parent's RESTRICT (migration 062)
    // and fail the whole pruneOrphanedFolders call.
    Files.createDirectories(classTempDir.resolve("Temp/2025"));
    Files.writeString(classTempDir.resolve("Temp/2025/datei.txt"), "Wird bald geloescht.");

    awaitJobCompletion(triggerIndexing());
    LibraryFolder temp = findFolder(null, "Temp").orElseThrow();
    LibraryFolder temp2025 = findFolder(temp.getId(), "2025").orElseThrow();

    // Since #886, AsyncIndexingExecutor's own StaleDocumentCleanupService call would remove this
    // document automatically on the next run - deleted here directly instead, to isolate and
    // exercise pruneOrphanedFolders's own folder-pruning behaviour on its own.
    Files.delete(classTempDir.resolve("Temp/2025/datei.txt"));
    Files.delete(classTempDir.resolve("Temp/2025"));
    Files.delete(classTempDir.resolve("Temp"));
    documentRepository.deleteAll(
        documentRepository.findAll().stream()
            .filter(d -> d.getFileName().equals("datei.txt"))
            .toList());

    awaitJobCompletion(triggerIndexing());

    assertThat(folderRepository.findById(temp2025.getId())).isEmpty();
    assertThat(folderRepository.findById(temp.getId())).isEmpty();
  }

  @Test
  void theSameContentInTwoSubdirectoriesRemainsTwoDistinctDocuments() throws IOException {
    // ADR-0020, Entscheidung 6: FILESYSTEM dedup is path-based, not checksum-based - two
    // identical files in different directories of the same source are two legitimate documents.
    Files.createDirectories(classTempDir.resolve("A"));
    Files.createDirectories(classTempDir.resolve("B"));
    Files.writeString(classTempDir.resolve("A/gleich.txt"), "Identischer Inhalt.");
    Files.writeString(classTempDir.resolve("B/gleich.txt"), "Identischer Inhalt.");

    IndexingJob job = triggerIndexing();
    awaitJobCompletion(job);

    var completedJob = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(completedJob.getDocumentsProcessed()).isEqualTo(2);

    List<Document> documents =
        documentRepository.findAll().stream()
            .filter(d -> d.getFileName().equals("gleich.txt"))
            .toList();
    assertThat(documents).hasSize(2);
    assertThat(documents.get(0).getChecksum()).isEqualTo(documents.get(1).getChecksum());
    assertThat(documents.stream().map(Document::getFolderId).distinct().count()).isEqualTo(2);

    LibraryFolder folderA = findFolder(null, "A").orElseThrow();
    LibraryFolder folderB = findFolder(null, "B").orElseThrow();
    assertThat(documents.stream().map(Document::getFolderId).toList())
        .containsExactlyInAnyOrder(folderA.getId(), folderB.getId());
  }
}
