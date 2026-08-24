package io.opaa.library;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.auth.CurrentUser;
import io.opaa.auth.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.DocumentSourceType;
import io.opaa.indexing.DocumentStatus;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs {@link UploadPendingRecoveryRunner} against a real Postgres database with the real,
 * versioned Liquibase schema applied - the same {@code spring.liquibase.enabled=true}, {@code
 * ddl-auto=none} pattern {@code LibraryDocumentServiceIntegrationTest} uses, needed here because
 * {@code documents.created_at} (migration 041) only exists once Liquibase has actually run
 * (AGENTS.md, "Reproduktionsnachweis").
 *
 * <p>Calls {@link UploadPendingRecoveryRunner#run} directly rather than relying on it having
 * already run once at this test class's own {@code @SpringBootTest} context startup - the rows this
 * test cares about do not exist yet at that point, so the automatic startup run is a harmless
 * no-op, and the assertions below need a run that happens after {@link #setUp} has seeded data.
 */
// Own @DynamicPropertySource (below, a short pending-recovery threshold) means Spring's context
// cache still keys this to its own context regardless of the shared @OpaaIntegrationTest base -
// documented exception per AGENTS.md.
@OpaaIntegrationTest
class UploadPendingRecoveryRunnerIntegrationTest {

  private static final ApplicationArguments NO_ARGS = new DefaultApplicationArguments();

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    // A short threshold so the test can distinguish "old enough" from "too recent" with second-
    // scale sleeps/offsets instead of the 30-minute production default.
    registry.add("opaa.upload.pending-recovery-threshold-minutes", () -> 1);
  }

  @Autowired private UploadPendingRecoveryRunner runner;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private User editor;
  private UUID libraryId;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org")).getId();
    editor = new User("editor-subject", "issuer", "editor@example.com", "Editor");
    editor.setOrganizationId(organizationId);
    editor = userRepository.save(editor);

    var libraryRequest = libraryCreation("Bibliothek", DocumentSourceType.UPLOAD).build();
    CurrentUser editorCaller =
        CurrentUser.of(editor.getId(), organizationId, SystemRole.USER, editor.getDisplayName());
    libraryId = libraryService.createLibrary(libraryRequest, editorCaller).library().getId();
  }

  @AfterEach
  void tearDown() {
    documentRepository.deleteAll();
    grantHistoryRepository.deleteBySubjectUserIdIn(List.of(editor.getId()));
    membershipHistoryRepository.deleteByUserIdIn(List.of(editor.getId()));
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE id = ?", libraryId);
    userRepository.deleteById(editor.getId());
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void marksOnlyStalePendingUploadsAsFailedOnStartup() {
    UUID stalePendingId =
        savePendingDocument("staleUpload.pdf", Instant.now().minus(2, ChronoUnit.HOURS));
    UUID freshPendingId = savePendingDocument("freshUpload.pdf", Instant.now());
    UUID staleIndexedId =
        saveIndexedDocument("staleIndexed.pdf", Instant.now().minus(2, ChronoUnit.HOURS));
    UUID staleConnectorPendingId =
        savePendingDocument(
            "staleConnector.pdf",
            Instant.now().minus(2, ChronoUnit.HOURS),
            DocumentSourceType.FILESYSTEM);

    runner.run(NO_ARGS);

    Document staleUpload = documentRepository.findById(stalePendingId).orElseThrow();
    assertThat(staleUpload.getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(staleUpload.getErrorMessage()).isEqualTo(UploadPendingRecoveryRunner.FAILURE_REASON);

    Document freshUpload = documentRepository.findById(freshPendingId).orElseThrow();
    assertThat(freshUpload.getStatus())
        .as("A PENDING row younger than the threshold is still legitimately in flight")
        .isEqualTo(DocumentStatus.PENDING);

    Document staleIndexed = documentRepository.findById(staleIndexedId).orElseThrow();
    assertThat(staleIndexed.getStatus())
        .as("Recovery only ever touches PENDING rows, never a row that already finished")
        .isEqualTo(DocumentStatus.INDEXED);

    Document staleConnectorPending =
        documentRepository.findById(staleConnectorPendingId).orElseThrow();
    assertThat(staleConnectorPending.getStatus())
        .as(
            "#614 covers only the upload path (PR #631 review, finding 2) - a stuck connector row"
                + " is #501's still-open RUNNING recovery to fix, not this runner's")
        .isEqualTo(DocumentStatus.PENDING);
  }

  private UUID savePendingDocument(String fileName, Instant createdAt) {
    return savePendingDocument(fileName, createdAt, DocumentSourceType.UPLOAD);
  }

  private UUID savePendingDocument(
      String fileName, Instant createdAt, DocumentSourceType sourceType) {
    Document document = newDocument(fileName, sourceType);
    document = documentRepository.save(document);
    backdateCreatedAt(document.getId(), createdAt);
    return document.getId();
  }

  private UUID saveIndexedDocument(String fileName, Instant createdAt) {
    Document document = newDocument(fileName, DocumentSourceType.UPLOAD);
    document.setStatus(DocumentStatus.INDEXED);
    document = documentRepository.save(document);
    backdateCreatedAt(document.getId(), createdAt);
    return document.getId();
  }

  private Document newDocument(String fileName, DocumentSourceType sourceType) {
    Document document =
        new Document(fileName, "/uploads/" + fileName, "application/pdf", 10L, sourceType);
    document.setLibraryId(libraryId);
    document.setOrganizationId(organizationId);
    document.setUploadedByUserId(editor.getId());
    return document;
  }

  private void backdateCreatedAt(UUID documentId, Instant createdAt) {
    jdbcTemplate.update(
        "UPDATE documents SET created_at = ? WHERE id = ?",
        java.sql.Timestamp.from(createdAt),
        documentId);
  }
}
