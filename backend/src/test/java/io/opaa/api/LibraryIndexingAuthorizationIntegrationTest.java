package io.opaa.api;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.indexing.DocumentSourceType;
import io.opaa.indexing.IndexingJobRepository;
import io.opaa.indexing.JobStatus;
import io.opaa.library.AssetGrant;
import io.opaa.library.AssetGrantRepository;
import io.opaa.library.AssetRole;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryVisibility;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaMockMvcTest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * #478/ADR-0018: proves the {@code EDITOR} authorization on {@code POST
 * /api/v1/libraries/{libraryId}/indexing} is real at the HTTP endpoint - and that the former {@code
 * SYSTEM_ADMIN} requirement of {@code POST /api/v1/indexing/trigger} is genuinely gone (ADR-0018,
 * Entscheidung 2), not merely bypassed by a permissive test security config. Runs the full {@code
 * dev} security chain ({@link DevAuthFilter}, {@code UserProvisioningFilter}) against a real
 * Postgres, mirroring {@code IndexingControllerAuthorizationIntegrationTest} this replaces.
 */
// Own @DynamicPropertySource (below, rate-limit disabled + a scoped filesystem allowlist) means
// Spring's context cache still keys this to its own context regardless of the shared
// @OpaaMockMvcTest base - documented exception per AGENTS.md. Previously also declared its own
// duplicate Postgres container and manually registered spring.datasource.* (issue #843) -
// removed, ServiceConnection now comes from @OpaaMockMvcTest's import.
@OpaaMockMvcTest
class LibraryIndexingAuthorizationIntegrationTest {

  @TempDir static Path documentDir;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    // #478 code review precedent (former IndexingControllerAuthorizationIntegrationTest): the
    // production default (opaa.rate-limit.indexing, 1 request per 60s) would otherwise turn every
    // second trigger in this class into an unrelated 429.
    registry.add("opaa.rate-limit.enabled", () -> false);
    // #484: overrides the dev profile's /data,/tmp default so this suite's own @TempDir stays
    // inside the allowlist.
    registry.add(
        "opaa.indexing.filesystem-allowlist", () -> documentDir.toAbsolutePath().toString());
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
    jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE name LIKE 'Test-Bibliothek%'");
    jdbcTemplate.update("DELETE FROM users WHERE email = 'foreign-owner-478@example.com'");

    // Provisions "dev-admin" as SYSTEM_ADMIN (opaa.auth.initial-admin-email matches its seeded
    // email, application.yml) via the real UserProvisioningFilter - triggered by any authenticated
    // request, not a hand-inserted row. GET .../indexing/status on a fresh, random id 404s but
    // still runs the filter chain.
    mockMvc.perform(
        get("/api/v1/libraries/" + UUID.randomUUID() + "/indexing/status").with(devUser(null)));
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
      return request;
    };
  }

  private void awaitJobFinished(UUID libraryId) {
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                org.assertj.core.api.Assertions.assertThat(
                        indexingJobRepository.existsByStatusAndLibraryIdAndOrganizationId(
                            JobStatus.RUNNING, libraryId, Organization.DEFAULT_ID))
                    .isFalse());
  }

  private KnowledgeLibrary createFilesystemLibrary(String name, UUID ownerId) throws IOException {
    KnowledgeLibrary library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                name,
                null,
                ownerId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.FILESYSTEM,
                documentDir.toAbsolutePath().toString(),
                null,
                null,
                null,
                false));
    return library;
  }

  @Test
  void systemAdminWithoutAGrantOnAForeignLibraryGetsNotFound() throws Exception {
    // ADR-0018, Entscheidung 2: EDITOR is required regardless of system-admin status - a system
    // admin without any grant must not be able to trigger a run into a library they do not own or
    // manage. #436: "no grant at all" answers 404, not 403 - a system admin's own lack of a grant
    // must not be distinguishable from the library not existing, matching what GET
    // /libraries/{id} already answers the same caller for the same library.
    UUID foreignLibraryId = createForeignLibraryWithNoGrantForDevAdmin().getId();

    mockMvc
        .perform(post("/api/v1/libraries/" + foreignLibraryId + "/indexing").with(devUser(null)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("Bibliothek nicht gefunden"));
  }

  @Test
  void aRegularUserWithAnExplicitEditorGrantSucceedsWithoutBeingSystemAdmin() throws Exception {
    // ADR-0018, Entscheidung 2: the former SYSTEM_ADMIN @PreAuthorize requirement is gone - an
    // ordinary EDITOR grant is now sufficient on its own. Owned by a foreign user (not devAdmin),
    // so the only thing that can possibly let devAdmin's trigger through is the EDITOR grant below.
    // No pre-grant trigger call here (unlike systemAdminWithoutAGrantOnAForeignLibraryGetsForbidden
    // above, which covers exactly that case): LibraryAccessService caches a library's grants
    // (grantsByLibrary), and this test bypasses AssetGrantService's own cache invalidation by
    // writing the grant directly - an earlier call on this same library would cache the pre-grant
    // (empty) state and make the assertion below flaky against a cache that has not expired yet.
    KnowledgeLibrary library = createForeignLibraryWithNoGrantForDevAdmin();

    grantRepository.save(
        AssetGrant.forUser(
            library.getId(),
            Organization.DEFAULT_ID,
            devAdmin.getId(),
            AssetRole.EDITOR,
            null,
            devAdmin.getId()));

    mockMvc
        .perform(post("/api/v1/libraries/" + library.getId() + "/indexing").with(devUser(null)))
        .andExpect(status().isAccepted());

    awaitJobFinished(library.getId());
  }

  @Test
  void aNonAdminUserWithAnExplicitEditorGrantSucceeds() throws Exception {
    // #500 review, finding 4: aRegularUserWithAnExplicitEditorGrantSucceedsWithoutBeingSystemAdmin
    // above actually runs as devAdmin (devUser(null) defaults to the configured default user, which
    // is SYSTEM_ADMIN) - it only proves the missing grant on that one library, not that a genuinely
    // non-privileged caller can trigger a run at all. "dev-user" (application.yml) does not match
    // opaa.auth.initial-admin-email, so UserProvisioningFilter provisions it as a plain USER.
    User regularUser = provisionDevUser();

    KnowledgeLibrary library = createForeignLibraryWithNoGrantForDevAdmin();
    grantRepository.save(
        AssetGrant.forUser(
            library.getId(),
            Organization.DEFAULT_ID,
            regularUser.getId(),
            AssetRole.EDITOR,
            null,
            devAdmin.getId()));

    mockMvc
        .perform(
            post("/api/v1/libraries/" + library.getId() + "/indexing").with(devUser("dev-user")))
        .andExpect(status().isAccepted());

    awaitJobFinished(library.getId());
  }

  private User provisionDevUser() throws Exception {
    mockMvc.perform(
        get("/api/v1/libraries/" + UUID.randomUUID() + "/indexing/status")
            .with(devUser("dev-user")));
    User user =
        userRepository.findAll().stream()
            .filter(u -> "dev-user@opaa.local".equals(u.getEmail()))
            .findFirst()
            .orElseThrow();
    org.assertj.core.api.Assertions.assertThat(user.getSystemRole()).isEqualTo(SystemRole.USER);
    return user;
  }

  @Test
  void anUploadLibraryIsRejectedWithConflict() throws Exception {
    KnowledgeLibrary library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Test-Bibliothek Upload",
                null,
                devAdmin.getId(),
                LibraryVisibility.PRIVATE,
                false));
    grantRepository.save(
        AssetGrant.forUser(
            library.getId(),
            Organization.DEFAULT_ID,
            devAdmin.getId(),
            AssetRole.EDITOR,
            null,
            devAdmin.getId()));

    mockMvc
        .perform(post("/api/v1/libraries/" + library.getId() + "/indexing").with(devUser(null)))
        .andExpect(status().isConflict());
  }

  @Test
  void aSecondTriggerOfTheSameLibraryWhileRunningIsRejectedButAnotherLibraryRunsInParallel()
      throws Exception {
    // #478 acceptance criteria: concurrency is per library - a second trigger of the *same*
    // library while a run is in progress is a 409, but a *different* library may run at the same
    // time.
    KnowledgeLibrary libraryA = createFilesystemLibrary("Test-Bibliothek A", devAdmin.getId());
    KnowledgeLibrary libraryB = createFilesystemLibrary("Test-Bibliothek B", devAdmin.getId());
    grantRepository.save(
        AssetGrant.forUser(
            libraryA.getId(),
            Organization.DEFAULT_ID,
            devAdmin.getId(),
            AssetRole.EDITOR,
            null,
            devAdmin.getId()));
    grantRepository.save(
        AssetGrant.forUser(
            libraryB.getId(),
            Organization.DEFAULT_ID,
            devAdmin.getId(),
            AssetRole.EDITOR,
            null,
            devAdmin.getId()));

    mockMvc
        .perform(post("/api/v1/libraries/" + libraryA.getId() + "/indexing").with(devUser(null)))
        .andExpect(status().isAccepted());

    // A different library's trigger must not be blocked by libraryA's run.
    mockMvc
        .perform(post("/api/v1/libraries/" + libraryB.getId() + "/indexing").with(devUser(null)))
        .andExpect(status().isAccepted());

    awaitJobFinished(libraryA.getId());
    awaitJobFinished(libraryB.getId());
  }

  private KnowledgeLibrary createForeignLibraryWithNoGrantForDevAdmin() throws IOException {
    UUID foreignOwnerId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'opaa-dev', 'foreign-owner-478@example.com',"
            + " 'Foreign Owner', now(), 'USER', ?)",
        foreignOwnerId,
        "foreign-owner-478-" + foreignOwnerId,
        Organization.DEFAULT_ID);

    return libraryRepository.save(
        KnowledgeLibrary.ownedByUser(
            Organization.DEFAULT_ID,
            "Test-Bibliothek Fremd",
            null,
            foreignOwnerId,
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.FILESYSTEM,
            documentDir.toAbsolutePath().toString(),
            null,
            null,
            null,
            false));
  }
}
