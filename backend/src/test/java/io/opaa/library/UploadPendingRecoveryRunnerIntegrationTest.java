package io.opaa.library;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.instance.CurrentInstance;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetGrantHistoryRepository;
import io.opaa.permission.GroupMembershipHistoryRepository;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import io.opaa.test.ScheduledRunLockRelease;
import io.opaa.test.SourceTypes;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs {@link UploadPendingRecoveryRunner} against a real Postgres database with the real,
 * versioned Liquibase schema applied - the same {@code spring.liquibase.enabled=true}, {@code
 * ddl-auto=none} pattern {@code LibraryDocumentServiceIntegrationTest} uses, needed here because
 * {@code documents.created_at} only exists once Liquibase has actually run (AGENTS.md,
 * "Reproduktionsnachweis").
 *
 * <p>Calls {@link UploadPendingRecoveryRunner#run} directly rather than relying on it having
 * already run once at this test class's own {@code @SpringBootTest} context startup - the rows this
 * test cares about do not exist yet at that point, so the automatic startup run is a harmless
 * no-op, and the assertions below need a run that happens after {@link #setUp} has seeded data.
 * Other instances are rows of the instance register whose heartbeat lies as far back as each test
 * needs (ADR-0053, Entscheidung 8).
 */
@OpaaIntegrationTest
class UploadPendingRecoveryRunnerIntegrationTest {

  private static final ApplicationArguments NO_ARGS = new DefaultApplicationArguments();

  @Autowired private UploadPendingRecoveryRunner runner;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;

  @Autowired private CurrentInstance currentInstance;

  private UUID organizationId;
  private User editor;
  private UUID libraryId;
  private final List<UUID> ownInstances = new ArrayList<>();

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org")).getId();
    editor = new User("editor-subject", "issuer", "editor@example.com", "Editor");
    editor.setOrganizationId(organizationId);
    editor = userRepository.save(editor);

    var libraryRequest = libraryCreation("Bibliothek", SourceType.UPLOAD).build();
    CurrentUser editorCaller =
        CurrentUser.of(editor.getId(), organizationId, SystemRole.USER, editor.getDisplayName());
    libraryId = libraryService.createLibrary(libraryRequest, editorCaller).library().getId();
  }

  @AfterEach
  void tearDown() {
    ownInstances.forEach(
        id -> jdbcTemplate.update("DELETE FROM backend_instances WHERE id = ?", id));
    ownInstances.clear();
    ownLibraryFixtures.removeLibraries(libraryId);
    grantHistoryRepository.deleteBySubjectUserIdIn(List.of(editor.getId()));
    membershipHistoryRepository.deleteByUserIdIn(List.of(editor.getId()));
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    // A library carries ownership intervals; their owner column is RESTRICT, so they have
    // to go before the accounts that hold them.
    jdbcTemplate.update(
        "DELETE FROM asset_ownership_history WHERE organization_id = ?", organizationId);
    userRepository.deleteById(editor.getId());
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void marksOnlyPendingUploadsOfDeadInstancesAsFailedOnStartup() {
    UUID dead = registerInstance(Duration.ofMinutes(10));
    UUID deadUpload = savePendingDocument("deadUpload.pdf", hoursAgo(2), dead);
    UUID ownUpload = savePendingDocument("ownUpload.pdf", hoursAgo(2), currentInstance.id());
    UUID legacyUpload = savePendingDocument("legacyUpload.pdf", hoursAgo(2), null);
    UUID deadIndexed = saveIndexedDocument("deadIndexed.pdf", hoursAgo(2), dead);
    UUID deadConnectorPending =
        savePendingDocument("deadConnector.pdf", hoursAgo(2), dead, SourceTypes.FILESYSTEM);

    runner.run(NO_ARGS);

    Document failed = documentRepository.findById(deadUpload).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(failed.getErrorMessage()).isEqualTo(UploadPendingRecoveryRunner.FAILURE_REASON);
    assertThat(statusOf(legacyUpload))
        .as("An upload written before the processing instance was recorded has no task left")
        .isEqualTo(DocumentStatus.FAILED);
    assertThat(statusOf(ownUpload))
        .as("An upload of a live instance is still in flight, however old")
        .isEqualTo(DocumentStatus.PENDING);
    assertThat(statusOf(deadIndexed))
        .as("Recovery only ever touches PENDING rows, never a row that already finished")
        .isEqualTo(DocumentStatus.INDEXED);
    assertThat(statusOf(deadConnectorPending))
        .as("Only the upload path - a stuck connector row is the indexing recovery's")
        .isEqualTo(DocumentStatus.PENDING);
  }

  /**
   * Regression guard: a starting instance failed every upload accepted before it started, also
   * those another, live instance had accepted seconds earlier and was still processing. Only the
   * heartbeat of the processing instance decides.
   */
  @Test
  void aStartingInstanceLeavesAFreshUploadOfAnotherLiveInstanceAlone() {
    UUID other = registerInstance(Duration.ZERO);
    UUID upload = savePendingDocument("acceptedByOther.pdf", hoursAgo(1), other);

    runner.run(NO_ARGS);

    assertThat(statusOf(upload))
        .as("The other instance is alive and still processing the upload")
        .isEqualTo(DocumentStatus.PENDING);
  }

  /** Without any restart, the uploads of an instance that died are failed by the next sweep. */
  @Test
  void theSweepFailsTheUploadsOfAnInstanceOnceItsHeartbeatLapsed() {
    UUID other = registerInstance(Duration.ZERO);
    UUID upload = savePendingDocument("acceptedByOther.pdf", Instant.now(), other);

    ScheduledRunLockRelease.releaseAll(jdbcTemplate);

    runner.recoverUploadsOfDeadInstances();
    assertThat(statusOf(upload)).isEqualTo(DocumentStatus.PENDING);

    jdbcTemplate.update(
        "UPDATE backend_instances SET heartbeat_at = clock_timestamp() - interval '10 minutes'"
            + " WHERE id = ?",
        other);
    ScheduledRunLockRelease.releaseAll(jdbcTemplate);
    runner.recoverUploadsOfDeadInstances();

    assertThat(statusOf(upload)).isEqualTo(DocumentStatus.FAILED);
  }

  private UUID registerInstance(Duration silentFor) {
    UUID id = UUID.randomUUID();
    ownInstances.add(id);
    jdbcTemplate.update(
        "INSERT INTO backend_instances (id, host_name, version, oldest_compatible_version,"
            + " started_at, heartbeat_at, fingerprint, fingerprint_hash) VALUES (?, 'fixture',"
            + " '0.0.0-dev', '0.0.0-dev', clock_timestamp() - interval '1 hour',"
            + " clock_timestamp() - make_interval(secs => ?), '{}', 'x')",
        id,
        (double) silentFor.toSeconds());
    return id;
  }

  private DocumentStatus statusOf(UUID documentId) {
    return documentRepository.findById(documentId).orElseThrow().getStatus();
  }

  private static Instant hoursAgo(int hours) {
    return Instant.now().minus(hours, ChronoUnit.HOURS);
  }

  private UUID savePendingDocument(String fileName, Instant createdAt, UUID instance) {
    return savePendingDocument(fileName, createdAt, instance, SourceType.UPLOAD);
  }

  private UUID savePendingDocument(
      String fileName, Instant createdAt, UUID instance, SourceType sourceType) {
    Document document = newDocument(fileName, sourceType);
    document = documentRepository.save(document);
    backdate(document.getId(), createdAt, instance);
    return document.getId();
  }

  private UUID saveIndexedDocument(String fileName, Instant createdAt, UUID instance) {
    Document document = newDocument(fileName, SourceType.UPLOAD);
    document.setStatus(DocumentStatus.INDEXED);
    document = documentRepository.save(document);
    backdate(document.getId(), createdAt, instance);
    return document.getId();
  }

  private Document newDocument(String fileName, SourceType sourceType) {
    Document document =
        new Document(fileName, "/uploads/" + fileName, "application/pdf", 10L, sourceType);
    document.setLibraryId(libraryId);
    document.setOrganizationId(organizationId);
    document.setUploadedByUserId(editor.getId());
    return document;
  }

  /** Set by SQL, so the rows look the same to a build whose entity does not map the column. */
  private void backdate(UUID documentId, Instant createdAt, UUID instance) {
    jdbcTemplate.update(
        "UPDATE documents SET created_at = ?, processing_instance_id = ? WHERE id = ?",
        java.sql.Timestamp.from(createdAt),
        instance,
        documentId);
  }
}
