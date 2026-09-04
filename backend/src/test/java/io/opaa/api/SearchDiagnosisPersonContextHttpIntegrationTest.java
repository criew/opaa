package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.DiagnosticTargetKind;
import io.opaa.api.types.GroupKind;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.diagnosticaccess.DiagnosticContextLogEntry;
import io.opaa.diagnosticaccess.DiagnosticContextLogRepository;
import io.opaa.diagnosticaccess.DiagnosticImpersonationGrant;
import io.opaa.diagnosticaccess.DiagnosticImpersonationGrantRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
import io.opaa.test.EmbeddingModelFakeConfiguration;
import io.opaa.test.OpaaMockMvcTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@code POST /api/v1/admin/search/diagnosis} with {@code contextType=USER} against the real
 * security chain, the real {@code ForeignDiagnosticContextService} and a real Postgres (#1150).
 * {@link SearchAdminControllerTest} mocks the diagnosis service out entirely and can therefore only
 * show that the request maps; the three properties that matter here - a run happens only with a
 * befugnis, it writes its protocol entry, and it never searches a diagnosegesperrte library - exist
 * nowhere in that slice.
 */
// Shares the EmbeddingModelFakeConfiguration context with PipelineReindexHttpIntegrationTest: a
// person-context run reaches the real retrieval pipeline, whose vector stage would otherwise dial
// the real, unreachable-in-CI embedding endpoint.
@OpaaMockMvcTest
@Import(EmbeddingModelFakeConfiguration.class)
class SearchDiagnosisPersonContextHttpIntegrationTest {

  private static final String QUESTION = "Was gilt bei Gebührenbefreiung wegen Bedürftigkeit?";
  private static final String JUSTIFICATION = "Beschwerde vom 02.09.2026, Vorgang 4711";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private GroupRepository groupRepository;
  @Autowired private DiagnosticImpersonationGrantRepository grantRepository;
  @Autowired private DiagnosticContextLogRepository logRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private User devAdmin;
  private UUID organizationId;
  private UUID targetUserId;
  private UUID orgUnitId;
  private UUID openLibraryId;
  private UUID lockedLibraryId;
  private Instant startedAt;

  private RequestPostProcessor devAdmin() {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, "dev-admin");
      return request;
    };
  }

  @BeforeEach
  void setUp() throws Exception {
    // Scopes every protocol assertion to this test method: entries of earlier methods in the
    // shared context are older than this instant, and the log is never deleted from here (the
    // application account holds no DELETE on it, ADR-0015).
    startedAt = Instant.now();
    // Provisions "dev-admin" as SYSTEM_ADMIN through the real UserProvisioningFilter, which runs
    // for any authenticated request - deliberately not the endpoint under test.
    mockMvc.perform(get("/api/v1/notifications").with(devAdmin())).andExpect(status().isOk());
    devAdmin =
        userRepository.findAll().stream()
            .filter(user -> "admin@opaa.local".equals(user.getEmail()))
            .findFirst()
            .orElseThrow();
    organizationId = devAdmin.getOrganizationId();

    User target = new User("person-1150", "test-issuer", "person-1150@example.com", "Thomas Klein");
    target.setOrganizationId(organizationId);
    targetUserId = userRepository.save(target).getId();

    orgUnitId =
        groupRepository
            .save(new Group(organizationId, GroupKind.ORG_UNIT, "Bürgerbüro", null, null, null))
            .getId();
    jdbcTemplate.update(
        "INSERT INTO group_memberships (id, user_id, group_id, organization_id, created_at)"
            + " VALUES (?, ?, ?, ?, now())",
        UUID.randomUUID(),
        targetUserId,
        orgUnitId,
        organizationId);

    openLibraryId = insertLibrary("Satzungen & Gebührenordnungen");
    lockedLibraryId = insertLibrary("Personalvorgänge");
    // Every library starts diagnosegesperrt (changeset 006); only the open one is unlocked here.
    jdbcTemplate.update(
        "UPDATE knowledge_libraries SET diagnostics_locked = false WHERE id = ?", openLibraryId);
    grantLibraryToTarget(openLibraryId);
    grantLibraryToTarget(lockedLibraryId);
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update(
        "DELETE FROM documents WHERE library_id in (?, ?)", openLibraryId, lockedLibraryId);
    jdbcTemplate.update(
        "DELETE FROM asset_grants WHERE library_id in (?, ?)", openLibraryId, lockedLibraryId);
    jdbcTemplate.update(
        "DELETE FROM knowledge_libraries WHERE id in (?, ?)", openLibraryId, lockedLibraryId);
    jdbcTemplate.update(
        "DELETE FROM diagnostic_impersonation_grants WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM group_memberships WHERE group_id = ?", orgUnitId);
    jdbcTemplate.update("DELETE FROM groups WHERE id = ?", orgUnitId);
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", targetUserId);
  }

  @Test
  void withoutTheBefugnisThePersonContextIsRefusedAndNothingIsRecorded() throws Exception {
    mockMvc
        .perform(personContextRequest(JUSTIFICATION))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Sicht als")));

    assertThat(protocolEntries()).isEmpty();
    mockMvc
        .perform(get("/api/v1/admin/search/diagnosis-context").with(devAdmin()))
        .andExpect(jsonPath("$.personContextAvailable").value(false))
        .andExpect(
            jsonPath("$.personContextHint")
                .value(org.hamcrest.Matchers.containsString("Sie halten keine")));
  }

  @Test
  void withTheBefugnisTheRunHappensLeavesOutTheLockedLibraryAndIsRecorded() throws Exception {
    grantBefugnis();

    mockMvc
        .perform(personContextRequest(JUSTIFICATION))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contextType").value("USER"))
        .andExpect(jsonPath("$.searchScope.length()").value(1))
        .andExpect(jsonPath("$.searchScope[0].id").value(openLibraryId.toString()))
        .andExpect(jsonPath("$.lockedLibraryCount").value(1));

    assertThat(protocolEntries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.getTargetKind()).isEqualTo(DiagnosticTargetKind.USER);
              assertThat(entry.getTestQuestion()).isEqualTo(QUESTION);
              assertThat(entry.getJustification()).isEqualTo(JUSTIFICATION);
              // The rights snapshot names the locked library as locked, never as searched.
              assertThat(entry.getPermissionSnapshot())
                  .contains("libraries=[" + openLibraryId + "]")
                  .contains("lockedLibraries=[" + lockedLibraryId + "]");
            });

    mockMvc
        .perform(get("/api/v1/admin/search/diagnosis-context").with(devAdmin()))
        .andExpect(jsonPath("$.personContextAvailable").value(true));
  }

  /**
   * A tracked document from a diagnosegesperrte library must be answered inside the same lock
   * context the search ran in: no file name, no library name, and not as a Rechtefrage - the target
   * person may well hold the right, the library was left out because of the lock (Leitplanke (e)).
   */
  @Test
  void aTrackedDocumentFromALockedLibraryIsNeitherNamedNorCalledARightsProblem() throws Exception {
    grantBefugnis();
    UUID documentId = insertDocument(lockedLibraryId, "personalakte-klein.pdf");

    mockMvc
        .perform(personContextRequest(JUSTIFICATION, documentId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.trackedDocument.outcome").value("IN_LOCKED_AREA"))
        .andExpect(jsonPath("$.trackedDocument.fileName").doesNotExist())
        .andExpect(jsonPath("$.trackedDocument.libraryName").doesNotExist())
        .andExpect(jsonPath("$.trackedDocument.libraryId").doesNotExist())
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("personalakte"))))
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Personalvorgänge"))));
  }

  /**
   * A document the target person genuinely may not read stays distinguishable from a locked one -
   * but is still not named, because it lies outside the scope this run searched.
   */
  @Test
  void aTrackedDocumentOutsideTheTargetsRightsIsReportedWithoutNamingIt() throws Exception {
    grantBefugnis();
    UUID foreignLibraryId = insertLibrary("Bauleitplanung");
    UUID documentId = insertDocument(foreignLibraryId, "bebauungsplan.pdf");
    try {
      mockMvc
          .perform(personContextRequest(JUSTIFICATION, documentId))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.trackedDocument.outcome").value("OUTSIDE_SEARCH_SCOPE"))
          .andExpect(jsonPath("$.trackedDocument.fileName").doesNotExist())
          .andExpect(jsonPath("$.trackedDocument.libraryName").doesNotExist());
    } finally {
      jdbcTemplate.update("DELETE FROM documents WHERE library_id = ?", foreignLibraryId);
      jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE id = ?", foreignLibraryId);
    }
  }

  @Test
  void aPersonContextWithoutAJustificationIsNotExecuted() throws Exception {
    grantBefugnis();

    mockMvc.perform(personContextRequest("   ")).andExpect(status().isBadRequest());

    assertThat(protocolEntries()).isEmpty();
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      personContextRequest(String justification) {
    return personContextRequest(justification, null);
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      personContextRequest(String justification, UUID trackedDocumentId) {
    return post("/api/v1/admin/search/diagnosis")
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"question":"%s","contextType":"USER","targetUserId":"%s","justification":"%s"%s}
            """
                .formatted(
                    QUESTION,
                    targetUserId,
                    justification,
                    trackedDocumentId == null
                        ? ""
                        : ",\"trackedDocumentId\":\"" + trackedDocumentId + "\""))
        .with(devAdmin());
  }

  private UUID insertDocument(UUID libraryId, String fileName) {
    UUID documentId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO documents (id, file_name, file_path, content_type, file_size, chunk_count,"
            + " indexed_at, checksum, status, source_type, library_id, organization_id, created_at)"
            + " VALUES (?, ?, ?, 'application/pdf', 1024, 1, now(), ?, 'INDEXED', 'UPLOAD', ?, ?,"
            + " now())",
        documentId,
        fileName,
        "person-context-it/" + documentId,
        "checksum-" + documentId,
        libraryId,
        organizationId);
    return documentId;
  }

  private List<DiagnosticContextLogEntry> protocolEntries() {
    return logRepository
        .findByTimeRange(
            organizationId,
            startedAt,
            Instant.now().plus(1, ChronoUnit.MINUTES),
            PageRequest.of(0, 10))
        .getContent();
  }

  private void grantBefugnis() {
    Instant now = Instant.now();
    grantRepository.save(
        new DiagnosticImpersonationGrant(
            organizationId,
            devAdmin.getId(),
            orgUnitId,
            now.minus(1, ChronoUnit.HOURS),
            now.plus(30, ChronoUnit.DAYS),
            devAdmin.getId(),
            now));
  }

  private UUID insertLibrary(String name) {
    UUID libraryId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, listed, source_type, created_at, updated_at)"
            + " VALUES (?, ?, ?, 'USER', ?, 'PRIVATE', false, 'UPLOAD', now(), now())",
        libraryId,
        organizationId,
        name,
        devAdmin.getId());
    return libraryId;
  }

  private void grantLibraryToTarget(UUID libraryId) {
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, library_id, organization_id, subject_type, subject_user_id,"
            + " role, created_at, updated_at) VALUES (?, ?, ?, 'USER', ?, 'VIEWER', now(), now())",
        UUID.randomUUID(),
        libraryId,
        organizationId,
        targetUserId);
  }
}
