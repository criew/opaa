package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.library.AssetGrant;
import io.opaa.library.AssetGrantRepository;
import io.opaa.library.AssetRole;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryVisibility;
import io.opaa.organization.Organization;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * #501 reproduction: a {@code RUNNING} {@code indexing_jobs} row with no {@code @Async} task behind
 * it (a discarded task, or - simulated here - a process that died mid-run) locks its library out of
 * every future trigger forever (409, {@code IndexingJobService#isJobRunning}), with nothing in the
 * UI to resolve it (#478's per-library concurrency turned what used to be a global inconvenience
 * into a permanent, per-library dead end). {@link IndexingJobRecoveryScheduler#recoverOnStartup}
 * must free the library the moment the application restarts; {@link
 * IndexingJobService#recoverStaleJobs} must free it even without one - but only once its heartbeat
 * has actually gone stale, not merely because the run has been going on for a while.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class IndexingJobRecoveryIntegrationTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  @TempDir static Path documentDir;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("opaa.rate-limit.enabled", () -> false);
    registry.add(
        "opaa.indexing.filesystem-allowlist", () -> documentDir.toAbsolutePath().toString());
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private IndexingJobRecoveryScheduler recoveryScheduler;
  @Autowired private IndexingJobService indexingJobService;

  private User devAdmin;

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

  private KnowledgeLibrary createFilesystemLibraryWithEditorGrant() {
    KnowledgeLibrary library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Test-Bibliothek Recovery " + UUID.randomUUID(),
                null,
                devAdmin.getId(),
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.FILESYSTEM,
                documentDir.toAbsolutePath().toString(),
                null,
                null,
                null,
                false));
    grantRepository.save(
        AssetGrant.forUser(
            library.getId(),
            Organization.DEFAULT_ID,
            devAdmin.getId(),
            AssetRole.EDITOR,
            null,
            devAdmin.getId()));
    return library;
  }

  /** Simulates a row left behind by a process that crashed mid-run - no backing task exists. */
  private IndexingJob seedOrphanedRunningJob(UUID libraryId) {
    var job = new IndexingJob(JobStatus.RUNNING);
    job.setLibraryId(libraryId);
    return indexingJobRepository.saveAndFlush(job);
  }

  /**
   * Simulates a running job whose heartbeat ({@link IndexingJob#getLastProgressAt()}) is {@code
   * heartbeatAge} in the past - a genuinely active run that has been running longer than that has a
   * far more recent heartbeat (#501 review, finding 1's whole point), so this stands in for either
   * a truly stale run (an old age) or an actively progressing one (a fresh age).
   */
  private IndexingJob seedRunningJobWithHeartbeat(UUID libraryId, Duration heartbeatAge) {
    var job = new IndexingJob(JobStatus.RUNNING);
    job.setLibraryId(libraryId);
    job.setLastProgressAt(Instant.now().minus(heartbeatAge));
    return indexingJobRepository.saveAndFlush(job);
  }

  @Test
  void anOrphanedRunningJobLocksTheLibraryUntilRecoveryRunsThenFreesItAgain() throws Exception {
    KnowledgeLibrary library = createFilesystemLibraryWithEditorGrant();
    IndexingJob orphaned = seedOrphanedRunningJob(library.getId());

    // Reproduces #501: the library is locked by a row with no task left to ever finish it.
    mockMvc
        .perform(post("/api/v1/libraries/" + library.getId() + "/indexing"))
        .andExpect(status().isConflict());

    // Simulates the next application startup noticing the orphaned row.
    recoveryScheduler.recoverOnStartup();

    IndexingJob recovered = indexingJobRepository.findById(orphaned.getId()).orElseThrow();
    assertThat(recovered.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(recovered.getErrorMessage()).isEqualTo("Durch Neustart abgebrochen");

    // The library is free again - a new trigger succeeds instead of 409ing forever.
    mockMvc
        .perform(post("/api/v1/libraries/" + library.getId() + "/indexing"))
        .andExpect(status().isAccepted());

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(
                        indexingJobRepository.existsByStatusAndLibraryId(
                            JobStatus.RUNNING, library.getId()))
                    .isFalse());
  }

  /**
   * #501 review, finding 2: the previous version of this test called the repository directly with a
   * cutoff manufactured in the future, so removing the {@code lastProgressAt} condition entirely
   * (or flipping its sign) would still have left this green - the assertion never depended on
   * *which* rows the query actually selected. This version seeds two libraries, one genuinely stale
   * (an old heartbeat) and one still actively progressing (a fresh heartbeat), and calls the real
   * entry point ({@link IndexingJobService#recoverStaleJobs}) with a realistic timeout - only the
   * stale row may end up FAILED.
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

    assertThat(recovered).isEqualTo(1);
    IndexingJob staleResult = indexingJobRepository.findById(staleJob.getId()).orElseThrow();
    assertThat(staleResult.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(staleResult.getErrorMessage())
        .isEqualTo("Indizierungslauf abgebrochen: verwaister Lauf (Zeitüberschreitung)");
    IndexingJob activeResult = indexingJobRepository.findById(activeJob.getId()).orElseThrow();
    assertThat(activeResult.getStatus()).isEqualTo(JobStatus.RUNNING);
  }
}
