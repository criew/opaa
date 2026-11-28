package io.opaa.indexing.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.indexing.attachment.StandaloneAttachmentAccess;
import io.opaa.indexing.job.IndexingJob;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.job.JobStatus;
import io.opaa.indexing.trigger.DocumentIndexingService;
import io.opaa.instance.CurrentInstance;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OpaaTestDirectory;
import io.opaa.test.OwnLibraryFixtures;
import io.opaa.test.SourceTypes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * A write of the ingest that loses its database connection - the backend serving it ends with
 * {@code pg_terminate_backend} in the middle of the final status update, as when the primary of a
 * replicated database goes away - is repeated once the pool hands out a working connection, for an
 * upload and for a document of an indexing run alike (ADR-0053, Entscheidung 8). A permanent
 * failure of the same write is not repeated, and a run given up meanwhile is not continued.
 *
 * <p>A trigger on {@code documents} interrupts the first update to {@code INDEXED} of a file named
 * {@code abriss-*} and counts every such update in a sequence, which no rollback takes back; it
 * exists only while a method of this class runs.
 */
@OpaaIntegrationTest
class ConnectionLossDuringWriteIntegrationTest {

  private static final Path sourceDir = OpaaTestDirectory.subdirectory("connection-loss-source");
  private static final Path uploadDir = OpaaTestDirectory.subdirectory("connection-loss-uploads");

  private static final String COUNTER = "connection_loss_it_writes";
  private static final String FUNCTION = "connection_loss_it_interrupt";
  private static final String TRIGGER = "connection_loss_it_interrupt";

  @Autowired private DocumentIngestService documentIngestService;
  @Autowired private DocumentIndexingService documentIndexingService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private CurrentInstance currentInstance;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;

  @Autowired
  @Qualifier("indexingTaskExecutor")
  private TaskExecutor indexingTaskExecutor;

  private UUID userId;
  private KnowledgeLibrary sourceLibrary;
  private KnowledgeLibrary uploadLibrary;

  @BeforeEach
  void setUp() throws IOException {
    dropInterruption();
    clear(sourceDir);
    clear(uploadDir);
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Abriss', now(), ?, ?)",
        userId,
        "connection-loss-it-" + userId,
        "connection-loss-it-" + userId + "@example.com",
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
    sourceLibrary =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Quelle",
                null,
                userId,
                SourceTypes.FILESYSTEM,
                sourceDir.toAbsolutePath().toString(),
                null,
                null));
    uploadLibrary =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(Organization.DEFAULT_ID, "Uploads", null, userId));
    grantOwner(sourceLibrary.getId());
  }

  @AfterEach
  void tearDown() throws IOException {
    awaitRunsEnded();
    dropInterruption();
    ownLibraryFixtures.removeLibraries(sourceLibrary.getId(), uploadLibrary.getId());
    jdbcTemplate.update("DELETE FROM asset_grants WHERE subject_user_id = ?", userId);
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    clear(sourceDir);
    clear(uploadDir);
  }

  @Test
  void anUploadWhoseWriteLosesItsConnectionIsRepeatedAndIndexed() throws IOException {
    interruptFirstIndexedWrite("terminate");

    Document upload = startUpload("abriss-aktennotiz.txt", "Aktennotiz zur Umschaltung.");

    Document done = awaitSettled(upload.getId());
    assertThat(done.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(indexedWrites()).as("the interrupted write and its repetition").isEqualTo(2);
    assertThat(chunksOf(upload.getId())).isEqualTo(done.getChunkCount()).isPositive();
  }

  @Test
  void aDocumentOfARunWhoseWriteLosesItsConnectionIsRepeatedAndIndexed() throws IOException {
    interruptFirstIndexedWrite("terminate");
    Files.writeString(sourceDir.resolve("abriss-vermerk.txt"), "Vermerk zur Umschaltung.");
    Files.writeString(sourceDir.resolve("anderes.txt"), "Ein anderer Vermerk.");

    IndexingJob job = awaitEnded(triggerRun());

    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(job.getDocumentsFailed()).isZero();
    assertThat(job.getDocumentsProcessed()).isEqualTo(2);
    Document interrupted = sourceDocument("abriss-vermerk.txt");
    assertThat(interrupted.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(indexedWrites()).isEqualTo(2);
    assertThat(chunksOf(interrupted.getId())).isEqualTo(interrupted.getChunkCount()).isPositive();
  }

  @Test
  void aPermanentFailureOfTheSameWriteIsNotRepeated() throws IOException {
    interruptFirstIndexedWrite("permanent");

    Document upload = startUpload("abriss-bescheid.txt", "Bescheid ohne Umschaltung.");

    assertThat(awaitSettled(upload.getId()).getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(indexedWrites()).as("a single attempt").isEqualTo(1);
  }

  @Test
  void aRunGivenUpWhileItsWriteWaitsForTheDatabaseIsNotContinued() throws IOException {
    interruptFirstIndexedWrite("terminate");
    Files.writeString(sourceDir.resolve("abriss-protokoll.txt"), "Protokoll zur Umschaltung.");

    IndexingJob job = triggerRun();
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(5))
        .until(() -> indexedWrites() >= 1);
    // as the recovery does for a run whose instance it finds dead
    jdbcTemplate.update(
        "UPDATE indexing_jobs SET status = 'FAILED', completed_at = now() WHERE id = ?",
        job.getId());
    awaitRunsEnded();

    assertThat(indexedWrites()).as("no write after the run was given up").isEqualTo(1);
    assertThat(sourceDocument("abriss-protokoll.txt").getStatus())
        .isNotEqualTo(DocumentStatus.INDEXED);
    assertThat(indexingJobRepository.findById(job.getId()).orElseThrow().getStatus())
        .isEqualTo(JobStatus.FAILED);
  }

  /**
   * {@code mode} {@code terminate} ends the backend's own connection in the middle of the update,
   * {@code permanent} fails it with a check violation; either only for the first such update.
   */
  private void interruptFirstIndexedWrite(String mode) {
    jdbcTemplate.execute("CREATE SEQUENCE " + COUNTER);
    jdbcTemplate.execute(
        "CREATE FUNCTION "
            + FUNCTION
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
            + " IF NEW.status = 'INDEXED' AND NEW.file_name LIKE 'abriss-%' THEN"
            + "  IF nextval('"
            + COUNTER
            + "') = 1 THEN"
            + "   IF TG_ARGV[0] = 'terminate' THEN"
            + "    PERFORM pg_terminate_backend(pg_backend_pid());"
            + "    PERFORM pg_sleep(10);"
            + "   ELSE"
            + "    RAISE EXCEPTION 'permanent write failure' USING ERRCODE = 'check_violation';"
            + "   END IF;"
            + "  END IF;"
            + " END IF;"
            + " RETURN NEW; END $$");
    jdbcTemplate.execute(
        "CREATE TRIGGER "
            + TRIGGER
            + " BEFORE UPDATE ON documents FOR EACH ROW EXECUTE FUNCTION "
            + FUNCTION
            + "('"
            + mode
            + "')");
  }

  private void dropInterruption() {
    jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + TRIGGER + " ON documents");
    jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + FUNCTION + "()");
    jdbcTemplate.execute("DROP SEQUENCE IF EXISTS " + COUNTER);
  }

  /** How many updates to {@code INDEXED} of an {@code abriss-*} file reached the trigger. */
  private long indexedWrites() {
    Boolean called = jdbcTemplate.queryForObject("SELECT is_called FROM " + COUNTER, Boolean.class);
    return Boolean.TRUE.equals(called)
        ? jdbcTemplate.queryForObject("SELECT last_value FROM " + COUNTER, Long.class)
        : 0;
  }

  /** The {@code PENDING} row and the hand-over to the upload pool, as an upload makes them. */
  private Document startUpload(String fileName, String content) throws IOException {
    Path file = Files.writeString(uploadDir.resolve(fileName), content);
    long size = Files.size(file);
    Document row =
        new Document(
            fileName, "uploads/" + UUID.randomUUID(), "text/plain", size, SourceType.UPLOAD);
    row.setLibraryId(uploadLibrary.getId());
    row.setOrganizationId(Organization.DEFAULT_ID);
    row.setProcessingInstanceId(currentInstance.id());
    Document saved = documentRepository.save(row);
    documentIngestService.processUploadedFileAsync(
        DocumentIngest.builder(uploadLibrary)
            .file(file, size)
            .filePath(saved.getFilePath())
            .fileName(fileName)
            .sourceType(SourceType.UPLOAD)
            .existingRow()
            .malwareScanned()
            .build(),
        new StandaloneAttachmentAccess(uploadLibrary, "Upload"),
        null);
    return saved;
  }

  private Document awaitSettled(UUID documentId) {
    await()
        .atMost(Duration.ofSeconds(90))
        .until(
            () ->
                documentRepository.findById(documentId).orElseThrow().getStatus()
                    != DocumentStatus.PENDING);
    return documentRepository.findById(documentId).orElseThrow();
  }

  private IndexingJob triggerRun() {
    return documentIndexingService.triggerIndexing(
        sourceLibrary.getId(),
        CurrentUser.of(userId, Organization.DEFAULT_ID, SystemRole.SYSTEM_ADMIN, null));
  }

  private IndexingJob awaitEnded(IndexingJob job) {
    await()
        .atMost(Duration.ofSeconds(90))
        .until(
            () ->
                indexingJobRepository.findById(job.getId()).orElseThrow().getStatus()
                    != JobStatus.RUNNING);
    awaitRunsEnded();
    return indexingJobRepository.findById(job.getId()).orElseThrow();
  }

  private void awaitRunsEnded() {
    ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) indexingTaskExecutor;
    await()
        .atMost(Duration.ofSeconds(90))
        .until(
            () ->
                executor.getActiveCount() == 0
                    && executor.getThreadPoolExecutor().getQueue().isEmpty());
  }

  private Document sourceDocument(String fileName) {
    return documentRepository.findByLibraryId(sourceLibrary.getId()).stream()
        .filter(document -> fileName.equals(document.getFileName()))
        .findFirst()
        .orElseThrow();
  }

  private int chunksOf(UUID documentId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM chunk_full_text WHERE document_id = ?", Integer.class, documentId);
  }

  private void grantOwner(UUID libraryId) {
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type,"
            + " subject_user_id, role, created_at, updated_at) VALUES (?, 'KNOWLEDGE_LIBRARY', ?,"
            + " ?, 'USER', ?, 'OWNER', now(), now())",
        UUID.randomUUID(),
        libraryId,
        Organization.DEFAULT_ID,
        userId);
  }

  private static void clear(Path dir) throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      for (Path file : files.toList()) {
        Files.deleteIfExists(file);
      }
    }
  }
}
