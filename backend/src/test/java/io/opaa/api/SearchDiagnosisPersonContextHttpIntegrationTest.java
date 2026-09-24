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
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSizeProperties;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.ProviderFixtures;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code POST /api/v1/admin/search/diagnosis} with {@code contextType=USER} against the real
 * security chain, the real {@code ForeignDiagnosticContextService} and a real Postgres (#1150).
 * {@link SearchAdminControllerTest} mocks the diagnosis service out entirely and can therefore only
 * show that the request maps; the three properties that matter here - a run happens only with a
 * befugnis, it writes its protocol entry, and it never searches a diagnosegesperrte library - exist
 * nowhere in that slice.
 */
@OpaaIntegrationTest
class SearchDiagnosisPersonContextHttpIntegrationTest {

  private static final String QUESTION = "Was gilt bei Gebührenbefreiung wegen Bedürftigkeit?";
  private static final String JUSTIFICATION = "Beschwerde vom 02.09.2026, Vorgang 4711";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private GroupRepository groupRepository;
  @Autowired private DiagnosticImpersonationGrantRepository grantRepository;
  @Autowired private DiagnosticContextLogRepository logRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private io.opaa.auth.oidc.OidcProviderRepository providerRepository;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private GroupSizeProperties groupSizeProperties;

  private User devAdmin;
  private UUID organizationId;
  private UUID targetUserId;
  private UUID targetWithoutLockedRightId;
  private UUID orgUnitId;
  private UUID providerId;
  private UUID openLibraryId;
  private UUID lockedLibraryId;
  private UUID ungrantedLockedLibraryId;
  private Instant startedAt;

  /** The accounts that only exist so the scope reaches the Mindestgruppengröße (#1879). */
  private final List<UUID> extraMemberIds = new ArrayList<>();

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

    User withoutLockedRight =
        new User("person-1259", "test-issuer", "person-1259@example.com", "Rita Vogel");
    withoutLockedRight.setOrganizationId(organizationId);
    targetWithoutLockedRightId = userRepository.save(withoutLockedRight).getId();

    // Every ORG_UNIT group carries its provider since #1816 (chk_groups_provider_kind).
    providerId = ProviderFixtures.tokenProvider(providerRepository).getId();
    orgUnitId =
        groupRepository
            .save(
                new Group(
                    organizationId,
                    GroupKind.ORG_UNIT,
                    "Bürgerbüro",
                    null,
                    providerId,
                    null,
                    null,
                    null))
            .getId();
    jdbcTemplate.update(
        "INSERT INTO group_memberships (id, user_id, group_id, organization_id, created_at)"
            + " VALUES (?, ?, ?, ?, now())",
        UUID.randomUUID(),
        targetUserId,
        orgUnitId,
        organizationId);
    jdbcTemplate.update(
        "INSERT INTO group_memberships (id, user_id, group_id, organization_id, created_at)"
            + " VALUES (?, ?, ?, ?, now())",
        UUID.randomUUID(),
        targetWithoutLockedRightId,
        orgUnitId,
        organizationId);
    // #1879: the scope of the befugnis has to reach at least the Mindestgruppengröße in active
    // accounts, at the time of every use - otherwise a group context discloses an individual. The
    // two target persons above are two of them; the rest are extras of this fixture.
    for (int index = 0; index < groupSizeProperties.minimumGroupSize() - 2; index++) {
      User extra =
          new User("colleague-" + index, "test-issuer", "colleague-" + index + "@example.com", "K");
      extra.setOrganizationId(organizationId);
      UUID extraId = userRepository.save(extra).getId();
      extraMemberIds.add(extraId);
      jdbcTemplate.update(
          "INSERT INTO group_memberships (id, user_id, group_id, organization_id, created_at)"
              + " VALUES (?, ?, ?, ?, now())",
          UUID.randomUUID(),
          extraId,
          orgUnitId,
          organizationId);
    }
    membershipResolver.invalidateUsers(extraMemberIds);

    openLibraryId = insertLibrary("Satzungen & Gebührenordnungen");
    lockedLibraryId = insertLibrary("Personalvorgänge");
    // Granted to nobody: it counts towards lockedLibraryCount all the same, which is what makes
    // the count a statement about the bestand rather than about the target person.
    ungrantedLockedLibraryId = insertLibrary("Personalrat");
    // Every library starts diagnosegesperrt (changeset 006); only the open one is unlocked here.
    jdbcTemplate.update(
        "UPDATE knowledge_libraries SET diagnostics_locked = false WHERE id = ?", openLibraryId);
    grantLibraryToTarget(openLibraryId, targetUserId);
    grantLibraryToTarget(lockedLibraryId, targetUserId);
    // Deliberately not granted the locked library: the second person may not read it at all.
    grantLibraryToTarget(openLibraryId, targetWithoutLockedRightId);
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update(
        "DELETE FROM documents WHERE library_id in (?, ?, ?)",
        openLibraryId,
        lockedLibraryId,
        ungrantedLockedLibraryId);
    jdbcTemplate.update(
        "DELETE FROM asset_grants WHERE asset_id in (?, ?, ?)",
        openLibraryId,
        lockedLibraryId,
        ungrantedLockedLibraryId);
    jdbcTemplate.update(
        "DELETE FROM assets WHERE id in (?, ?, ?)",
        openLibraryId,
        lockedLibraryId,
        ungrantedLockedLibraryId);
    jdbcTemplate.update(
        "DELETE FROM diagnostic_impersonation_grants WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM group_memberships WHERE group_id = ?", orgUnitId);
    jdbcTemplate.update("DELETE FROM groups WHERE id = ?", orgUnitId);
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", targetUserId);
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", targetWithoutLockedRightId);
    extraMemberIds.forEach(userId -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId));
    extraMemberIds.clear();
    // fk_groups_provider is RESTRICT, so the provider goes after the unit above. A provider row
    // left behind would make the next class's first provider not the default one.
    jdbcTemplate.update("DELETE FROM oidc_providers WHERE id = ?", providerId);
  }

  /**
   * #1879: the scope is measured at the time of the use. The body carries the stable code, so an
   * interface can tell this refusal from "you hold no befugnis at all" - and the message names no
   * figure, because below the Mindestgruppengröße the house withholds it (ADR-0036, Entscheidung
   * 9).
   */
  @Test
  void aScopeThatShrankBelowTheMinimumRefusesTheRunWithItsOwnCode() throws Exception {
    grantBefugnis();
    jdbcTemplate.update(
        "UPDATE users SET directory_locked_at = now() WHERE id = ANY(CAST(? AS uuid[]))",
        extraMemberIds.stream()
            .map(UUID::toString)
            .collect(java.util.stream.Collectors.joining(",", "{", "}")));
    membershipResolver.invalidateUsers(extraMemberIds);

    mockMvc
        .perform(personContextRequest(JUSTIFICATION))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("IMPERSONATION_SCOPE_NOT_USABLE"))
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("kleine Gruppe")))
        .andExpect(
            jsonPath("$.error")
                .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.matchesRegex(".*\\d.*"))));

    mockMvc
        .perform(get("/api/v1/admin/search/diagnosis-context").with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.personContextAvailable").value(false))
        .andExpect(
            jsonPath("$.personContextHint")
                .value(org.hamcrest.Matchers.containsString("Sie halten eine Befugnis")));
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
        // Two, not one: the target person may read only one of the two locked libraries, and
        // the reported number must not depend on that.
        .andExpect(jsonPath("$.lockedLibraryCount").value(2));

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
    // Unlocked on purpose: only then is "outside the scope" a rights matter and not the lock.
    jdbcTemplate.update(
        "UPDATE knowledge_libraries SET diagnostics_locked = false WHERE id = ?", foreignLibraryId);
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
      jdbcTemplate.update("DELETE FROM assets WHERE id = ?", foreignLibraryId);
    }
  }

  /**
   * The verdict on a document in a diagnosegesperrte library, and the reported number of locked
   * libraries, follow from the lock alone. Were either intersected with the target person's read
   * rights, the two answers below would differ - and "may this person read a locked library?" would
   * be answerable with any test question (Leitplanke (e), #1259).
   */
  @Test
  void theAnswerDoesNotDependOnWhetherTheTargetMayReadTheLockedLibrary() throws Exception {
    grantBefugnis();
    UUID documentId = insertDocument(lockedLibraryId, "personalakte-klein.pdf");

    String withReadRight = lockRelevantAnswer(targetUserId, documentId);
    String withoutReadRight = lockRelevantAnswer(targetWithoutLockedRightId, documentId);

    assertThat(withoutReadRight).isEqualTo(withReadRight);
  }

  /**
   * The whole answer, minus the three things that differ between two runs for reasons that have
   * nothing to do with the lock: the timestamp, the sub-queries (a model call decides them) and the
   * stage notes (they quote those sub-queries and their number). What remains covers the searched
   * scope, the locked-library count, every stage with its status, counts and verdicts, the
   * Endauswahl, the resolved document titles and the tracked-document verdict.
   */
  private String lockRelevantAnswer(UUID target, UUID trackedDocumentId) throws Exception {
    String body =
        mockMvc
            .perform(personContextRequest(target, JUSTIFICATION, trackedDocumentId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ObjectNode answer = (ObjectNode) new ObjectMapper().readTree(body);
    answer.remove("executedAt");
    answer.remove("searchQueries");
    ArrayNode stages = (ArrayNode) answer.get("stages");
    for (int i = 0; i < stages.size(); i++) {
      ((ObjectNode) stages.get(i)).remove("notes");
    }
    return answer.toString();
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
    return personContextRequest(targetUserId, justification, trackedDocumentId);
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      personContextRequest(UUID target, String justification, UUID trackedDocumentId) {
    return post("/api/v1/admin/search/diagnosis")
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"question":"%s","contextType":"USER","targetUserId":"%s","justification":"%s"%s}
            """
                .formatted(
                    QUESTION,
                    target,
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
        "WITH shell AS (INSERT INTO assets (id, asset_type, organization_id, name, owner_type,"
            + " owner_user_id, visibility, listed) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?, 'USER', ?, 'PRIVATE', false)"
            + " RETURNING id, organization_id) INSERT INTO knowledge_libraries (id,"
            + " organization_id, source_type) SELECT id, organization_id, 'UPLOAD' FROM shell",
        libraryId,
        organizationId,
        name,
        devAdmin.getId());
    return libraryId;
  }

  private void grantLibraryToTarget(UUID libraryId, UUID userId) {
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type, subject_user_id,"
            + " role, created_at, updated_at) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?, 'USER', ?, 'VIEWER', now(), now())",
        UUID.randomUUID(),
        libraryId,
        organizationId,
        userId);
  }
}
