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
 * IndexingJobRepository#failStaleRunningJobs} must free it even without one.
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

  @Test
  void aStaleRunningJobIsFreedByThePeriodicSweepWithoutARestart() {
    KnowledgeLibrary library = createFilesystemLibraryWithEditorGrant();
    IndexingJob stale = seedOrphanedRunningJob(library.getId());

    // Even without a restart, a run stuck RUNNING for far longer than any plausible
    // staleJobTimeout must eventually be recovered by the periodic sweep - the cutoff is set in
    // the future here so this row (whenever it was actually started) always counts as stale.
    int recovered =
        indexingJobRepository.failStaleRunningJobs(
            "test-stale", Instant.now().plusSeconds(60), Instant.now());

    assertThat(recovered).isEqualTo(1);
    IndexingJob result = indexingJobRepository.findById(stale.getId()).orElseThrow();
    assertThat(result.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(result.getErrorMessage()).isEqualTo("test-stale");
  }
}
