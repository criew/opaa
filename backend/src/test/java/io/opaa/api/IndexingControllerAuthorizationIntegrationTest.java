package io.opaa.api;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.indexing.IndexingJobRepository;
import io.opaa.indexing.JobStatus;
import io.opaa.library.AssetGrant;
import io.opaa.library.AssetGrantRepository;
import io.opaa.library.AssetRole;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryVisibility;
import io.opaa.organization.Organization;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PR #431 review, Befund 2: proves the {@code EDITOR} authorization on {@code POST
 * /api/v1/indexing/trigger} is real at the HTTP endpoint, not just inside {@link
 * io.opaa.indexing.DocumentIndexingService} called directly with {@code systemAdmin=false} - the
 * only value a unit test can set, but never the value the endpoint itself produces, since
 * {@code @PreAuthorize("hasRole('SYSTEM_ADMIN')")} means every caller who reaches the controller
 * already has {@code systemAdmin=true}. Runs the full {@code dev} security chain ({@link
 * DevAuthFilter}, {@code UserProvisioningFilter}, real {@code @PreAuthorize} method security)
 * against a real Postgres so the SYSTEM_ADMIN authority and the EDITOR grant check both come from
 * production code, not from a mocked service or a permissive test security config.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class IndexingControllerAuthorizationIntegrationTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  @TempDir static Path emptyDocumentDir;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("opaa.indexing.document-path", () -> emptyDocumentDir.toAbsolutePath().toString());
    // This test triggers /api/v1/indexing/trigger repeatedly (once in setUp to provision
    // dev-admin, once per test) - the production default (opaa.rate-limit.indexing, 1 request per
    // 60s) would otherwise turn every second call into an unrelated 429, not the 403/202 this
    // test is actually about.
    registry.add("opaa.rate-limit.enabled", () -> false);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IndexingJobRepository indexingJobRepository;

  private User devAdmin;

  @BeforeEach
  void setUp() throws Exception {
    // Defensive: a job left RUNNING by the previous test method (same shared context/Postgres
    // across methods in this class) would make DocumentIndexingService#triggerIndexing throw
    // IndexingAlreadyRunningException before even validating libraryId - awaited here too, not
    // only at the end of the tests that start a run, so this holds regardless of method order.
    awaitNoJobRunning();

    jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE name = 'Fremde Bibliothek'");
    jdbcTemplate.update("DELETE FROM users WHERE email = 'foreign-owner-419@example.com'");

    // Provisions "dev-admin" as SYSTEM_ADMIN (opaa.auth.initial-admin-email matches its seeded
    // email, application.yml) via the real UserProvisioningFilter - the same path every request
    // goes through, not a hand-inserted row.
    mockMvc.perform(post("/api/v1/indexing/trigger").with(devUser(null)).content("{}"));
    devAdmin =
        userRepository.findAll().stream()
            .filter(u -> "admin@opaa.local".equals(u.getEmail()))
            .findFirst()
            .orElseThrow();
    org.assertj.core.api.Assertions.assertThat(devAdmin.getSystemRole())
        .isEqualTo(SystemRole.SYSTEM_ADMIN);
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor devUser(
      String subject) {
    return request -> {
      if (subject != null) {
        request.addHeader(DevAuthFilter.DEV_USER_HEADER, subject);
      }
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }

  /**
   * Coordinator follow-up on the review: a test that actually starts a run (202) must wait for it
   * to finish before returning, or the next test in this class - sharing the same {@code
   * indexing_jobs} table via the class-level Testcontainer - can hit {@code
   * IndexingAlreadyRunningException} (409) instead of the 202/403 it expects. {@code
   * emptyDocumentDir} has no files, so the async run completes almost immediately; the timeout is
   * generous only to absorb scheduling jitter, not because real work is expected to take that long.
   */
  private void awaitNoJobRunning() {
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                org.assertj.core.api.Assertions.assertThat(
                        indexingJobRepository.existsByStatus(JobStatus.RUNNING))
                    .isFalse());
  }

  @Test
  void systemAdminWithoutAGrantOnAForeignLibraryGetsForbidden() throws Exception {
    // #419 acceptance criteria, PR #431 review Befund 2: a system admin without any grant must
    // not be able to write into a library they do not own or manage - even though the endpoint
    // itself requires SYSTEM_ADMIN, that role alone must not satisfy the EDITOR check.
    UUID foreignLibraryId = createForeignLibraryWithNoGrantForDevAdmin();

    mockMvc
        .perform(
            post("/api/v1/indexing/trigger")
                .with(devUser(null))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"libraryId\":\"" + foreignLibraryId + "\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("Kein Zugriff auf diese Bibliothek"));
  }

  @Test
  void systemAdminWithAnExplicitEditorGrantOnAForeignLibrarySucceeds() throws Exception {
    UUID foreignLibraryId = createForeignLibraryWithNoGrantForDevAdmin();
    grantRepository.save(
        AssetGrant.forUser(
            foreignLibraryId,
            Organization.DEFAULT_ID,
            devAdmin.getId(),
            AssetRole.EDITOR,
            null,
            devAdmin.getId()));

    mockMvc
        .perform(
            post("/api/v1/indexing/trigger")
                .with(devUser(null))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"libraryId\":\"" + foreignLibraryId + "\"}"))
        .andExpect(status().isAccepted());

    awaitNoJobRunning();
  }

  @Test
  void systemAdminMayTargetTheSystemLibraryWithoutAnExplicitGrant() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/indexing/trigger")
                .with(devUser(null))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"libraryId\":\"" + KnowledgeLibrary.SYSTEM_LIBRARY_ID + "\"}"))
        .andExpect(status().isAccepted());

    awaitNoJobRunning();
  }

  private UUID createForeignLibraryWithNoGrantForDevAdmin() throws IOException {
    UUID foreignOwnerId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'opaa-dev', 'foreign-owner-419@example.com',"
            + " 'Foreign Owner', now(), 'USER', ?)",
        foreignOwnerId,
        "foreign-owner-419-" + foreignOwnerId,
        Organization.DEFAULT_ID);

    KnowledgeLibrary foreignLibrary =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Fremde Bibliothek",
                null,
                foreignOwnerId,
                LibraryVisibility.PRIVATE,
                false,
                false));
    return foreignLibrary.getId();
  }
}
