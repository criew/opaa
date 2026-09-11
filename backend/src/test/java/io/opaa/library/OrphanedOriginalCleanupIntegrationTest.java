package io.opaa.library;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.indexing.document.AttachmentFilePath;
import io.opaa.indexing.document.Document;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The cleanup against real {@code documents} rows in Postgres: what decides is the row's {@code
 * file_path}, not its status, source type or parent. A {@code PENDING} row - an upload still being
 * processed - and a {@code FAILED} one keep their original just as an {@code INDEXED} row does, and
 * an attachment row's synthetic path belongs to no stored original at all.
 *
 * <p>The store is a filesystem adapter over this test's own temporary directory rather than the
 * application's configured one, so the assertions stay independent of {@code
 * opaa.upload.storage-path} and the class needs no property override of its own (context cache,
 * AGENTS.md).
 */
@OpaaIntegrationTest
class OrphanedOriginalCleanupIntegrationTest {

  private static final int GRACE_MINUTES = 60;

  @TempDir Path storageDir;

  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final Instant now = Instant.parse("2026-09-11T12:00:00Z");

  private UUID organizationId;
  private User editor;
  private UUID libraryId;
  private FilesystemUploadedOriginalStore store;
  private OrphanedOriginalCleanupService service;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org")).getId();
    editor = new User("orphan-editor-subject", "issuer", "orphan-editor@example.com", "Editor");
    editor.setOrganizationId(organizationId);
    editor = userRepository.save(editor);
    CurrentUser editorCaller =
        CurrentUser.of(editor.getId(), organizationId, SystemRole.USER, editor.getDisplayName());
    libraryId =
        libraryService
            .createLibrary(
                libraryCreation("Bibliothek", DocumentSourceType.UPLOAD).build(), editorCaller)
            .library()
            .getId();
    UploadProperties uploadProperties =
        new UploadProperties(storageDir.toString(), null, 1024L, null, 0, GRACE_MINUTES);
    store = new FilesystemUploadedOriginalStore(uploadProperties);
    service =
        new OrphanedOriginalCleanupService(
            libraryRepository,
            documentRepository,
            store,
            uploadProperties,
            Clock.fixed(now, ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    // One statement for parent and attachment alike: deleting the parent row on its own would
    // trip fk_documents_parent (ADR-0022).
    jdbcTemplate.update("DELETE FROM documents WHERE library_id = ?", libraryId);
    grantHistoryRepository.deleteBySubjectUserIdIn(List.of(editor.getId()));
    membershipHistoryRepository.deleteByUserIdIn(List.of(editor.getId()));
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE id = ?", libraryId);
    userRepository.deleteById(editor.getId());
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void everyRowOfTheLibraryProtectsItsOriginalWhateverItsStatusOrParent() throws IOException {
    String pending = storedOriginal("noch in Arbeit");
    String failed = storedOriginal("fehlgeschlagen");
    String indexed = storedOriginal("fertig");
    String orphan = storedOriginal("verwaist");
    row("pending.pdf", pending, DocumentStatus.PENDING, null);
    row("failed.pdf", failed, DocumentStatus.FAILED, null);
    UUID parentId = row("mail.eml", indexed, DocumentStatus.INDEXED, null);
    row(
        "anlage.pdf",
        AttachmentFilePath.of(indexed, 0, "anlage.pdf"),
        DocumentStatus.INDEXED,
        parentId);

    OrphanedOriginalReport report = service.report(organizationId, libraryId, null);

    assertThat(report.orphans()).extracting(OrphanedOriginal::locator).containsExactly(orphan);
    assertThat(report.scannedCount()).isEqualTo(4);
    assertThat(report.orphanCount()).isEqualTo(1);

    OrphanedOriginalDeletion deletion =
        service.delete(organizationId, libraryId, List.of(orphan, pending));

    assertThat(deletion.deleted()).containsExactly(orphan);
    assertThat(deletion.skipped())
        .containsExactly(
            new OrphanedOriginalDeletion.Skipped(pending, OrphanedOriginalSkipReason.REFERENCED));
    assertThat(Path.of(orphan)).doesNotExist();
    assertThat(Path.of(pending)).exists();
    assertThat(Path.of(failed)).exists();
    assertThat(Path.of(indexed)).exists();
  }

  /** A stored original of the library, old enough to be past the grace period. */
  private String storedOriginal(String content) throws IOException {
    UploadedOriginalRef ref =
        store
            .accept(
                libraryId,
                ".pdf",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)))
            .store();
    Files.setLastModifiedTime(
        Path.of(ref.locator()), FileTime.from(now.minus(Duration.ofHours(2))));
    return ref.locator();
  }

  private UUID row(String fileName, String filePath, DocumentStatus status, UUID parentId) {
    Document document =
        new Document(fileName, filePath, "application/pdf", 10L, DocumentSourceType.UPLOAD);
    document.setLibraryId(libraryId);
    document.setOrganizationId(organizationId);
    document.setUploadedByUserId(editor.getId());
    document.setStatus(status);
    document.setParentDocumentId(parentId);
    return documentRepository.save(document).getId();
  }
}
