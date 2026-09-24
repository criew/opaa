package io.opaa.searchadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSizeProperties;
import io.opaa.query.SearchedLibraryRef;
import io.opaa.test.OpaaIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The space context of a Rechteprofil-Lauf and the five protections that make it admissible (#1835,
 * ADR-0036 Entscheidung 7): the intersected Suchbereich, the Mindestgruppengröße counted over every
 * path into the space and checked at the moment of the run, one protocol entry per run with a space
 * and none without, and the diagnosis staying SYSTEM_ADMIN-only.
 */
@OpaaIntegrationTest
class SearchDiagnosisSpaceContextIntegrationTest {

  private static final UUID DEFAULT_ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired private SearchDiagnosisService diagnosisService;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MockMvc mockMvc;

  private UUID adminId;
  private UUID spaceId;
  private UUID profileGroupId;
  private UUID libraryInSpaceAndProfile;
  private UUID libraryInProfileOnly;
  private UUID libraryInSpaceOnly;
  private final List<UUID> memberIds = new ArrayList<>();

  private CurrentUser admin() {
    return CurrentUser.of(adminId, DEFAULT_ORGANIZATION_ID, SystemRole.SYSTEM_ADMIN, null);
  }

  @BeforeEach
  void setUp() {
    adminId = insertUser("Diagnose-Admin", SystemRole.SYSTEM_ADMIN);
    profileGroupId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO groups (id, organization_id, kind, name, created_at, updated_at, dissolved)"
            + " VALUES (?, ?, 'AD_HOC', 'Referat 50', now(), now(), false)",
        profileGroupId,
        DEFAULT_ORGANIZATION_ID);

    libraryInSpaceAndProfile = insertLibrary("Satzungen");
    libraryInProfileOnly = insertLibrary("Gebührenordnungen");
    libraryInSpaceOnly = insertLibrary("Bauleitplanung");
    grantToGroup(libraryInSpaceAndProfile);
    grantToGroup(libraryInProfileOnly);

    spaceId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO spaces (id, name, owner_id, created_at, updated_at, visibility,"
            + " organization_id, is_default, archived)"
            + " VALUES (?, 'Projekt Ost', ?, now(), now(), 'PRIVATE', ?, false, false)",
        spaceId,
        adminId,
        DEFAULT_ORGANIZATION_ID);
    associate(libraryInSpaceAndProfile);
    associate(libraryInSpaceOnly);
    // The group itself is a member of the space, with the usual reach of its members.
    jdbcTemplate.update(
        "INSERT INTO space_memberships (id, subject_type, group_id, space_id, role, created_at,"
            + " organization_id) VALUES (?, 'GROUP', ?, ?, 'MEMBER', now(), ?)",
        UUID.randomUUID(),
        profileGroupId,
        spaceId,
        DEFAULT_ORGANIZATION_ID);
    // One above the minimum, so a test can take two accounts out and still tell the two reasons
    // apart: too few members, or too few of them active.
    for (int i = 0; i < GroupSizeProperties.ENFORCED_MINIMUM + 1; i++) {
      addGroupMember(insertUser("Mitglied " + i, SystemRole.USER));
    }
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM space_asset_associations WHERE space_id = ?", spaceId);
    jdbcTemplate.update("DELETE FROM space_memberships WHERE space_id = ?", spaceId);
    jdbcTemplate.update("DELETE FROM spaces WHERE id = ?", spaceId);
    jdbcTemplate.update("DELETE FROM asset_grants WHERE subject_group_id = ?", profileGroupId);
    jdbcTemplate.update(
        "DELETE FROM assets WHERE id in (?, ?, ?)",
        libraryInSpaceAndProfile,
        libraryInProfileOnly,
        libraryInSpaceOnly);
    jdbcTemplate.update("DELETE FROM group_memberships WHERE group_id = ?", profileGroupId);
    jdbcTemplate.update("DELETE FROM groups WHERE id = ?", profileGroupId);
    jdbcTemplate.update(
        "DELETE FROM audit_log WHERE event_type = ? AND object_id = ?",
        AuditEventType.SEARCH_DIAGNOSIS_PROFILE_RUN.name(),
        spaceId.toString());
    memberIds.forEach(membershipResolver::invalidateUser);
    jdbcTemplate.update("DELETE FROM audit_actor_pseudonyms WHERE user_id = ?", adminId);
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", adminId);
    memberIds.forEach(id -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", id));
    memberIds.clear();
  }

  @Test
  void aProfileRunInASpaceSearchesTheIntersectionOfBoth() {
    SearchDiagnosis diagnosis = diagnosisService.diagnose(admin(), profileQuery(spaceId));

    assertThat(diagnosis.searchScope())
        .extracting(SearchedLibraryRef::id)
        .as("only what the space holds and the profile may read")
        .containsExactly(libraryInSpaceAndProfile);
  }

  /** Without a space the run keeps the whole organization-wide reach of the profile. */
  @Test
  void aProfileRunWithoutASpaceIsUnchanged() {
    SearchDiagnosis diagnosis = diagnosisService.diagnose(admin(), profileQuery(null));

    assertThat(diagnosis.searchScope())
        .extracting(SearchedLibraryRef::id)
        .contains(libraryInSpaceAndProfile, libraryInProfileOnly)
        .doesNotContain(libraryInSpaceOnly);
  }

  /**
   * The Mindestgruppengröße is checked when the run happens, not when the profile was picked: a
   * group that was big enough yesterday can be a single person today, and then the sight is a
   * person context without its Vollmacht.
   */
  @Test
  void aGroupThatShrinksBelowTheMinimumIsRefusedAtTheMomentOfTheRun() {
    diagnosisService.diagnose(admin(), profileQuery(spaceId));

    removeGroupMember(memberIds.get(0));
    removeGroupMember(memberIds.get(1));

    assertThatThrownBy(() -> diagnosisService.diagnose(admin(), profileQuery(spaceId)))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Person");
  }

  /**
   * ADR-0036, Entscheidung 7, Schutzpunkt 3 (#1818): gezählt werden aktive Konten, nicht
   * Mitgliedschaftszeilen. Six members, two of them locked by the directory synchronisation, are a
   * four-person context - and that is below the Mindestgruppengröße.
   */
  @Test
  void accountsLockedByTheDirectorySynchronisationDoNotCount() {
    diagnosisService.diagnose(admin(), profileQuery(spaceId));

    lockFromDirectory(memberIds.get(0));
    lockFromDirectory(memberIds.get(1));

    assertThatThrownBy(() -> diagnosisService.diagnose(admin(), profileQuery(spaceId)))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("aktive Konten");
  }

  /**
   * The count is the intersection with "reaches the space at all", not the size of the group: a
   * large group whose members are not in the space is exactly the case the protection is for.
   */
  @Test
  void aLargeGroupWithoutReachIntoTheSpaceIsRefused() {
    jdbcTemplate.update(
        "DELETE FROM space_memberships WHERE space_id = ? AND group_id = ?",
        spaceId,
        profileGroupId);

    assertThatThrownBy(() -> diagnosisService.diagnose(admin(), profileQuery(spaceId)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void onlyARunWithASpaceContextIsProtocolled() {
    diagnosisService.diagnose(admin(), profileQuery(null));
    assertThat(profileRunEvents()).isEmpty();

    diagnosisService.diagnose(admin(), profileQuery(spaceId));

    List<java.util.Map<String, Object>> events = profileRunEvents();
    assertThat(events).as("one entry per run, none per query").hasSize(1);
    assertThat(events.get(0).get("subject_ref"))
        .as("the group id, so the entry carries no person")
        .isEqualTo(profileGroupId.toString());
  }

  @Test
  void aSpaceContextIsRefusedForTheOtherContextTypes() {
    assertThatThrownBy(
            () ->
                diagnosisService.diagnose(
                    admin(),
                    new DiagnosisQuery(
                        "Frage", DiagnosisContextType.SELF, null, spaceId, null, null, null, null)))
        .isInstanceOf(ValidationException.class);
  }

  /**
   * ADR-0036, Entscheidung 7, Punkt 5: the diagnosis stays SYSTEM_ADMIN-only. Every capability is
   * delivered to "Alle Konten", so a plain account holds them all - and still gets 403 here.
   */
  @Test
  void theDiagnosisStaysClosedToAnAccountWithoutTheSystemAdminRole() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/search/diagnosis")
                .header(DevAuthFilter.DEV_USER_HEADER, "dev-user")
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .content("{\"question\":\"Frage\",\"contextType\":\"SELF\"}"))
        .andExpect(status().isForbidden());
  }

  private DiagnosisQuery profileQuery(UUID space) {
    return new DiagnosisQuery(
        "Gebührenbefreiung",
        DiagnosisContextType.PERMISSION_PROFILE,
        profileGroupId,
        space,
        null,
        null,
        null,
        null);
  }

  private List<java.util.Map<String, Object>> profileRunEvents() {
    return jdbcTemplate.queryForList(
        "SELECT subject_ref, \"after\" FROM audit_log WHERE event_type = ? AND object_id = ?",
        AuditEventType.SEARCH_DIAGNOSIS_PROFILE_RUN.name(),
        spaceId.toString());
  }

  private UUID insertUser(String displayName, SystemRole role) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, ?, now(), ?, ?)",
        id,
        "space-context-" + id,
        "space-context-" + id + "@example.com",
        displayName,
        role.name(),
        DEFAULT_ORGANIZATION_ID);
    return id;
  }

  private UUID insertLibrary(String name) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "WITH shell AS (INSERT INTO assets (id, asset_type, organization_id, name, owner_type,"
            + " owner_user_id, listed) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?, 'USER', ?, false)"
            + " RETURNING id, organization_id) INSERT INTO knowledge_libraries (id,"
            + " organization_id, source_type) SELECT id, organization_id, 'UPLOAD' FROM shell",
        id,
        DEFAULT_ORGANIZATION_ID,
        name,
        adminId);
    return id;
  }

  private void grantToGroup(UUID libraryId) {
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type,"
            + " subject_group_id, role, created_at, updated_at)"
            + " VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?, 'GROUP', ?, 'VIEWER', now(), now())",
        UUID.randomUUID(),
        libraryId,
        DEFAULT_ORGANIZATION_ID,
        profileGroupId);
  }

  private void associate(UUID libraryId) {
    jdbcTemplate.update(
        "INSERT INTO space_asset_associations (id, space_id, asset_id, organization_id,"
            + " created_by_user_id, created_at) VALUES (?, ?, ?, ?, ?, now())",
        UUID.randomUUID(),
        spaceId,
        libraryId,
        DEFAULT_ORGANIZATION_ID,
        adminId);
  }

  private void addGroupMember(UUID userId) {
    jdbcTemplate.update(
        "INSERT INTO group_memberships (id, user_id, group_id, organization_id, created_at)"
            + " VALUES (?, ?, ?, ?, now())",
        UUID.randomUUID(),
        userId,
        profileGroupId,
        DEFAULT_ORGANIZATION_ID);
    membershipResolver.invalidateUser(userId);
    memberIds.add(userId);
  }

  /** Takes the access away the way the directory run does (#1818). */
  private void lockFromDirectory(UUID userId) {
    jdbcTemplate.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", userId);
  }

  private void removeGroupMember(UUID userId) {
    jdbcTemplate.update(
        "DELETE FROM group_memberships WHERE group_id = ? AND user_id = ?", profileGroupId, userId);
    membershipResolver.invalidateUser(userId);
  }
}
