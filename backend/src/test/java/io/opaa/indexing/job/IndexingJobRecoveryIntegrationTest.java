package io.opaa.indexing.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.instance.CurrentInstance;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.library.UploadPendingRecoveryRunner;
import io.opaa.organization.Organization;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OpaaTestDirectory;
import io.opaa.test.OwnLibraryFixtures;
import io.opaa.test.ScheduledRunLockRelease;
import io.opaa.test.SecondInstance;
import io.opaa.test.SourceTypes;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Who gives up a {@code RUNNING} {@code indexing_jobs} row and a {@code PENDING} upload, with two
 * instances against one database (ADR-0053, Entscheidung 8): this context is one instance, a lean
 * {@link SecondInstance} the other. A row with no task behind it locks its library out of every
 * future trigger (409), so the recovery must free it once its owner is dead - and only then: the
 * runs of a live instance stay, however long ago they began, and a run given up never writes on.
 */
@OpaaIntegrationTest
class IndexingJobRecoveryIntegrationTest {

  // Underneath the suite-wide allowlisted base directory, so it needs no allowlist entry of its
  // own.
  private static final Path documentDir = OpaaTestDirectory.subdirectory("indexing-job-recovery");

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private IndexingJobRecoveryScheduler recoveryScheduler;
  @Autowired private IndexingJobService indexingJobService;
  @Autowired private UploadPendingRecoveryRunner uploadRecovery;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private CurrentInstance currentInstance;
  @Autowired private ConfigurableEnvironment environment;
  @Autowired private DataSource dataSource;
  @Autowired private Clock clock;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  private User devAdmin;

  /**
   * The libraries a test method created. The shared dev admin owns other classes' libraries too, so
   * the teardown goes by these ids, never by owner.
   */
  private final List<UUID> ownLibraryIds = new ArrayList<>();

  private final List<SecondInstance> secondInstances = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    mockMvc.perform(get("/api/v1/libraries/" + UUID.randomUUID() + "/indexing/status"));
    devAdmin =
        userRepository.findAll().stream()
            .filter(u -> "admin@opaa.local".equals(u.getEmail()))
            .findFirst()
            .orElseThrow();
    assertThat(devAdmin.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
  }

  /** Runs, uploads and grants go with their library (see {@link OwnLibraryFixtures}). */
  @AfterEach
  void tearDown() {
    ownLibraryFixtures.removeLibraries(ownLibraryIds.toArray(new UUID[0]));
    ownLibraryIds.clear();
    secondInstances.forEach(SecondInstance::close);
    secondInstances.clear();
  }

  /**
   * Startet B, während A einen Lauf und einen Upload verarbeitet: Both were begun before B - and
   * before this process - started, and both run to their end.
   */
  @Test
  void aStartingInstanceLeavesTheRunAndTheUploadOfALiveInstanceToRunToTheirEnd() {
    KnowledgeLibrary library = createFilesystemLibraryWithEditorGrant();
    UUID owner = currentInstance.id();
    IndexingJob run = seedRunningJob(library.getId(), owner, Duration.ofHours(1));
    UUID upload = seedPendingUpload(library.getId(), owner, Duration.ofHours(1));

    secondInstance();
    recoveryScheduler.recoverOnStartup();
    ScheduledRunLockRelease.releaseAll(jdbcTemplate);
    recoveryScheduler.recoverRunsOfDeadInstances();
    uploadRecovery.run(new DefaultApplicationArguments());
    ScheduledRunLockRelease.releaseAll(jdbcTemplate);
    uploadRecovery.recoverUploadsOfDeadInstances();

    assertThat(statusOf(run)).isEqualTo(JobStatus.RUNNING);
    assertThat(statusOfDocument(upload)).isEqualTo(DocumentStatus.PENDING);
    indexingJobService.updateProgress(run.getId(), 1, 0, 0, 1);
    assertThat(indexingJobService.completeJob(run.getId(), 1, 0, 0, 1)).isTrue();
    assertThat(documentRepository.markIndexed(upload, 1, Instant.now())).isOne();
    assertThat(statusOf(run)).isEqualTo(JobStatus.COMPLETED);
    assertThat(statusOfDocument(upload)).isEqualTo(DocumentStatus.INDEXED);
  }

  /**
   * Fällt A aus, recovert B deren Lauf und Upload erst nach Ablauf des Heartbeats - and a second
   * run of the library only ever starts after the first was given up, while the given-up one cannot
   * report on.
   */
  @Test
  void aFailedInstanceIsRecoveredOnceItsHeartbeatLapsedAndNeverNextToASecondRun() throws Exception {
    KnowledgeLibrary library = createFilesystemLibraryWithEditorGrant();
    SecondInstance failing = secondInstance();
    IndexingJob zombie = seedRunningJob(library.getId(), failing.id(), Duration.ofMinutes(5));
    UUID upload = seedPendingUpload(library.getId(), failing.id(), Duration.ofMinutes(5));

    ScheduledRunLockRelease.releaseAll(jdbcTemplate);

    recoveryScheduler.recoverRunsOfDeadInstances();
    ScheduledRunLockRelease.releaseAll(jdbcTemplate);
    uploadRecovery.recoverUploadsOfDeadInstances();
    assertThat(statusOf(zombie)).isEqualTo(JobStatus.RUNNING);
    assertThat(statusOfDocument(upload)).isEqualTo(DocumentStatus.PENDING);
    mockMvc
        .perform(post("/api/v1/libraries/" + library.getId() + "/indexing"))
        .andExpect(status().isConflict());

    failing.fail();
    ScheduledRunLockRelease.releaseAll(jdbcTemplate);
    recoveryScheduler.recoverRunsOfDeadInstances();
    ScheduledRunLockRelease.releaseAll(jdbcTemplate);
    uploadRecovery.recoverUploadsOfDeadInstances();

    IndexingJob recovered = indexingJobRepository.findById(zombie.getId()).orElseThrow();
    assertThat(recovered.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(recovered.getErrorMessage()).isEqualTo(IndexingJobService.RESTART_ABORTED_MESSAGE);
    assertThat(statusOfDocument(upload)).isEqualTo(DocumentStatus.FAILED);
    mockMvc
        .perform(post("/api/v1/libraries/" + library.getId() + "/indexing"))
        .andExpect(status().isAccepted());
    assertThatThrownBy(() -> indexingJobService.updateProgress(zombie.getId(), 7, 0, 0, 7))
        .isInstanceOf(RunAbandonedException.class);
    assertThat(statusOf(zombie)).isEqualTo(JobStatus.FAILED);
    assertThat(runningJobsOf(library.getId())).isLessThanOrEqualTo(1);
    await().atMost(30, TimeUnit.SECONDS).until(() -> runningJobsOf(library.getId()) == 0);
  }

  @Test
  void theRunsOfAnInstanceThatSignedOffAreRecoveredAtOnce() {
    KnowledgeLibrary library = createFilesystemLibraryWithEditorGrant();
    SecondInstance stopping = secondInstance();
    IndexingJob run = seedRunningJob(library.getId(), stopping.id(), Duration.ZERO);

    stopping.close();
    secondInstances.remove(stopping);
    ScheduledRunLockRelease.releaseAll(jdbcTemplate);
    recoveryScheduler.recoverRunsOfDeadInstances();

    assertThat(statusOf(run)).isEqualTo(JobStatus.FAILED);
  }

  /** A row written before owners were recorded is given up at the start, never by the sweep. */
  @Test
  void aRunWithoutOwnerIsGivenUpAtTheStartOnly() {
    KnowledgeLibrary library = createFilesystemLibraryWithEditorGrant();
    IndexingJob run = seedRunningJob(library.getId(), null, Duration.ofMinutes(1));

    ScheduledRunLockRelease.releaseAll(jdbcTemplate);

    recoveryScheduler.recoverRunsOfDeadInstances();
    assertThat(statusOf(run)).isEqualTo(JobStatus.RUNNING);

    recoveryScheduler.recoverOnStartup();
    assertThat(statusOf(run)).isEqualTo(JobStatus.FAILED);
  }

  /**
   * Ein Heartbeat setzt einen recoverten Lauf nicht auf RUNNING zurück: the recovery fails the row
   * and holds its lock while a progress report of the run waits for it; once the recovery commits,
   * the report must find the run given up instead of writing it back as running.
   */
  @Test
  void aProgressReportWaitingForTheRecoveryDoesNotTurnTheRunBackIntoARunningOne() throws Exception {
    KnowledgeLibrary library = createFilesystemLibraryWithEditorGrant();
    IndexingJob run = seedRunningJob(library.getId(), currentInstance.id(), Duration.ZERO);
    CompletableFuture<Void> report = new CompletableFuture<>();

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            recovery -> {
              jdbcTemplate.update(
                  "UPDATE indexing_jobs SET status = 'FAILED', error_message = ?,"
                      + " completed_at = now() WHERE id = ? AND status = 'RUNNING'",
                  IndexingJobService.RESTART_ABORTED_MESSAGE,
                  run.getId());
              CompletableFuture.runAsync(
                  () -> {
                    try {
                      indexingJobService.updateProgress(run.getId(), 3, 0, 0, 3);
                      report.complete(null);
                    } catch (RuntimeException e) {
                      report.completeExceptionally(e);
                    }
                  });
              await()
                  .atMost(10, TimeUnit.SECONDS)
                  .until(() -> sessionsWaitingToUpdate("indexing_jobs") > 0);
            });
    try {
      report.get(10, TimeUnit.SECONDS);
    } catch (java.util.concurrent.ExecutionException e) {
      assertThat(e.getCause()).isInstanceOf(RunAbandonedException.class);
    }

    IndexingJob after = indexingJobRepository.findById(run.getId()).orElseThrow();
    assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(after.getErrorMessage()).isEqualTo(IndexingJobService.RESTART_ABORTED_MESSAGE);
  }

  /**
   * Only a run whose heartbeat has actually gone stale is given up for hanging, not one that has
   * merely been running for a while: one library holds a stale heartbeat, the other a fresh one,
   * and the real entry point runs with a realistic timeout.
   */
  @Test
  void recoverStaleJobsOnlyFailsRunsPastTheConfiguredTimeoutNotActivelyProgressingOnes() {
    Duration staleJobTimeout = Duration.ofHours(4);
    KnowledgeLibrary staleLibrary = createFilesystemLibraryWithEditorGrant();
    KnowledgeLibrary activeLibrary = createFilesystemLibraryWithEditorGrant();
    IndexingJob staleJob =
        seedRunningJobWithHeartbeat(staleLibrary.getId(), staleJobTimeout.plusHours(1));
    IndexingJob activeJob =
        seedRunningJobWithHeartbeat(activeLibrary.getId(), Duration.ofSeconds(1));

    int recovered = indexingJobService.recoverStaleJobs(staleJobTimeout);

    // The recovery runs over every RUNNING row of the shared database; which rows it hit is
    // asserted per row below, not through this suite-wide count.
    assertThat(recovered).isPositive();
    IndexingJob staleResult = indexingJobRepository.findById(staleJob.getId()).orElseThrow();
    assertThat(staleResult.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(staleResult.getErrorMessage())
        .isEqualTo("Indizierungslauf abgebrochen: verwaister Lauf (Zeitüberschreitung)");
    IndexingJob activeResult = indexingJobRepository.findById(activeJob.getId()).orElseThrow();
    assertThat(activeResult.getStatus()).isEqualTo(JobStatus.RUNNING);
  }

  private SecondInstance secondInstance() {
    SecondInstance instance = SecondInstance.start(environment, dataSource, clock);
    secondInstances.add(instance);
    return instance;
  }

  private KnowledgeLibrary createFilesystemLibraryWithEditorGrant() {
    KnowledgeLibrary library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Test-Bibliothek Recovery " + UUID.randomUUID(),
                null,
                devAdmin.getId(),
                SourceTypes.FILESYSTEM,
                documentDir.toAbsolutePath().toString(),
                null,
                null));
    ownLibraryIds.add(library.getId());
    grantRepository.save(
        AssetGrant.forUser(
            KnowledgeLibrary.ASSET_TYPE,
            library.getId(),
            Organization.DEFAULT_ID,
            devAdmin.getId(),
            AssetRole.EDITOR,
            null,
            devAdmin.getId()));
    return library;
  }

  /** A running row of {@code owner} ({@code null} for none), started {@code age} ago. */
  private IndexingJob seedRunningJob(UUID libraryId, UUID owner, Duration age) {
    var job = new IndexingJob(JobStatus.RUNNING);
    job.setLibraryId(libraryId);
    job.setOrganizationId(Organization.DEFAULT_ID);
    job.setOwnerInstanceId(owner);
    IndexingJob saved = indexingJobRepository.saveAndFlush(job);
    jdbcTemplate.update(
        "UPDATE indexing_jobs SET started_at = clock_timestamp() - make_interval(secs => ?)"
            + " WHERE id = ?",
        (double) age.toSeconds(),
        saved.getId());
    return saved;
  }

  /** A pending upload {@code instance} processes, accepted {@code age} ago. */
  private UUID seedPendingUpload(UUID libraryId, UUID instance, Duration age) {
    Document document =
        new Document(
            "upload-" + UUID.randomUUID() + ".pdf",
            "/uploads/" + UUID.randomUUID() + ".pdf",
            "application/pdf",
            10L,
            SourceType.UPLOAD);
    document.setLibraryId(libraryId);
    document.setOrganizationId(Organization.DEFAULT_ID);
    document.setUploadedByUserId(devAdmin.getId());
    UUID id = documentRepository.save(document).getId();
    jdbcTemplate.update(
        "UPDATE documents SET processing_instance_id = ?,"
            + " created_at = clock_timestamp() - make_interval(secs => ?) WHERE id = ?",
        instance,
        (double) age.toSeconds(),
        id);
    return id;
  }

  /**
   * A running job whose heartbeat ({@link IndexingJob#getLastProgressAt()}) is {@code heartbeatAge}
   * in the past - a genuinely active run has a far more recent heartbeat, so this stands in for
   * either a truly stale run (an old age) or an actively progressing one (a fresh age).
   */
  private IndexingJob seedRunningJobWithHeartbeat(UUID libraryId, Duration heartbeatAge) {
    var job = new IndexingJob(JobStatus.RUNNING);
    job.setLibraryId(libraryId);
    job.setOrganizationId(Organization.DEFAULT_ID);
    job.setOwnerInstanceId(currentInstance.id());
    job.setLastProgressAt(Instant.now().minus(heartbeatAge));
    return indexingJobRepository.saveAndFlush(job);
  }

  private JobStatus statusOf(IndexingJob job) {
    return indexingJobRepository.findById(job.getId()).orElseThrow().getStatus();
  }

  private DocumentStatus statusOfDocument(UUID documentId) {
    return documentRepository.findById(documentId).orElseThrow().getStatus();
  }

  private long runningJobsOf(UUID libraryId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM indexing_jobs WHERE library_id = ? AND status = 'RUNNING'",
            Long.class,
            libraryId);
    return count == null ? 0 : count;
  }

  private long sessionsWaitingToUpdate(String table) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                + " AND query ILIKE ?",
            Long.class,
            "%update " + table + "%");
    return count == null ? 0 : count;
  }
}
