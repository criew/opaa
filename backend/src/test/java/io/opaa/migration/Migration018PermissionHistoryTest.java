package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Applies Liquibase changelog 018 in isolation against a database built from the real, versioned
 * changelog through changeSet 016 - the same pattern as {@code
 * Migration016VectorStoreLibraryMetadataTest}, with {@code test-master-through-016.yaml} as the
 * pre-migration fixture (changeSet 017 belongs to a different, parallel issue and is deliberately
 * not part of this fixture). {@code connection.setAutoCommit(true)} is called after every {@code
 * liquibase.update(...)} call, and the public schema is dropped and recreated between test methods,
 * per the package Javadoc's mandatory teardown pattern.
 *
 * <p>{@code setUp} deliberately does <b>not</b> apply changelog 018 itself (unlike an earlier
 * version of this class) - the backfill tests need legacy rows in {@code asset_grants}/{@code
 * group_memberships}/{@code knowledge_libraries} inserted <em>before</em> 018 runs, so every test
 * calls {@link #applyChangelog018()} itself, at the point in its own body where the migration is
 * meant to run.
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration018PermissionHistoryTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-016.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  // ---------------------------------------------------------------------------------------
  // asset_grant_history
  // ---------------------------------------------------------------------------------------

  @Test
  void chkAssetGrantHistorySubjectRejectsEveryMismatchBetweenSubjectTypeAndSubjectColumns()
      throws Exception {
    applyChangelog018();
    UUID library = insertLibrary(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());
    UUID group = insertGroup(UUID.randomUUID());

    assertThatThrownBy(() -> insertGrantHistory(library, "USER", null, null, "VIEWER", "GRANTED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_asset_grant_history_subject");
    assertThatThrownBy(() -> insertGrantHistory(library, "USER", null, group, "VIEWER", "GRANTED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_asset_grant_history_subject");
    assertThatThrownBy(() -> insertGrantHistory(library, "GROUP", null, null, "VIEWER", "GRANTED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_asset_grant_history_subject");
    assertThatThrownBy(() -> insertGrantHistory(library, "GROUP", user, null, "VIEWER", "GRANTED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_asset_grant_history_subject");
  }

  @Test
  void chkAssetGrantHistoryCauseRejectsAnUnknownCause() throws Exception {
    applyChangelog018();
    UUID library = insertLibrary(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());

    assertThatThrownBy(() -> insertGrantHistory(library, "USER", user, null, "VIEWER", "REJECTED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_asset_grant_history_cause");
  }

  @Test
  void chkAssetGrantHistoryCauseAcceptsBackfill() throws Exception {
    applyChangelog018();
    UUID library = insertLibrary(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());

    insertGrantHistory(library, "USER", user, null, "VIEWER", "BACKFILL");

    assertThat(grantHistoryCountFor(library)).isEqualTo(1);
  }

  @Test
  void chkAssetGrantHistoryRoleRejectsARoleNameFromTheDisjointSpaceRoleSystem() throws Exception {
    applyChangelog018();
    UUID library = insertLibrary(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());

    assertThatThrownBy(() -> insertGrantHistory(library, "USER", user, null, "ADMIN", "GRANTED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_asset_grant_history_role");
  }

  @Test
  void uniqueIndexRejectsASecondOpenIntervalForTheSameLibraryAndSubject() throws Exception {
    applyChangelog018();
    UUID library = insertLibrary(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());

    insertGrantHistory(library, "USER", user, null, "VIEWER", "GRANTED");
    assertThatThrownBy(
            () -> insertGrantHistory(library, "USER", user, null, "MANAGER", "ROLE_CHANGED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_asset_grant_history_open_user");

    // A closed interval (valid_to set) does not conflict with a later open one for the same
    // library/subject - the whole point of a temporal history.
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "UPDATE asset_grant_history SET valid_to = now() WHERE library_id = '"
              + library
              + "' AND subject_user_id = '"
              + user
              + "'");
    }
    insertGrantHistory(library, "USER", user, null, "MANAGER", "ROLE_CHANGED");
    assertThat(grantHistoryCountFor(library)).isEqualTo(2);
  }

  @Test
  void deletingTheActorNullsTheReferenceInsteadOfBlockingTheDeletionOrLosingTheInterval()
      throws Exception {
    // #238, per
    // docs/features/security-and-compliance.md#nachweisbarkeit-historisierung-von-rechten:
    // deleting an account must clear the actor reference, not block the deletion or lose the
    // historised interval itself.
    applyChangelog018();
    UUID library = insertLibrary(UUID.randomUUID());
    UUID subject = insertUser(UUID.randomUUID());
    UUID actor = insertUser(UUID.randomUUID());
    UUID historyId = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO asset_grant_history (id, library_id, organization_id, subject_type,"
              + " subject_user_id, role, cause, actor_user_id, valid_from, created_at) VALUES ('"
              + historyId
              + "', '"
              + library
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'USER', '"
              + subject
              + "', 'OWNER', 'GRANTED', '"
              + actor
              + "', now(), now())");
      statement.execute("DELETE FROM users WHERE id = '" + actor + "'");
    }

    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT actor_user_id FROM asset_grant_history WHERE id = '" + historyId + "'")) {
      rs.next();
      assertThat(rs.getObject(1)).isNull();
    }
    assertThat(grantHistoryCountFor(library)).isEqualTo(1);
  }

  @Test
  void deletingTheSubjectUserIsBlockedWhileTheirGrantHistoryExists() throws Exception {
    // Code review of #238, finding 4: the subject side must not be weaker than RESTRICT - a
    // CASCADE here would delete exactly the interval a Stichtag reconstruction for a departed
    // person depends on.
    applyChangelog018();
    UUID library = insertLibrary(UUID.randomUUID());
    UUID subject = insertUser(UUID.randomUUID());
    insertGrantHistory(library, "USER", subject, null, "VIEWER", "GRANTED");

    try (Statement statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.execute("DELETE FROM users WHERE id = '" + subject + "'"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("fk_asset_grant_history_subject_user");
    }
    assertThat(grantHistoryCountFor(library)).isEqualTo(1);
  }

  @Test
  void deletingALibraryLeavesItsGrantHistoryIntact() throws Exception {
    // Code review of #238, finding 3: library_id carries no foreign key at all - a library
    // deletion (KnowledgeLibraryService#deleteLibrary, a routine OWNER action) must never take the
    // record of who could once read it down with it.
    applyChangelog018();
    UUID library = insertLibrary(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());
    insertGrantHistory(library, "USER", user, null, "OWNER", "GRANTED");
    assertThat(grantHistoryCountFor(library)).isEqualTo(1);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM knowledge_libraries WHERE id = '" + library + "'");
    }

    assertThat(grantHistoryCountFor(library)).isEqualTo(1);
  }

  @Test
  void deletingAGroupLeavesItsGrantHistoryIntact() throws Exception {
    // Code review of #238, finding 3 - the group-subject counterpart of the library test above.
    // A group that once held a grant but had it revoked can be deleted
    // (AssetGrantRepository#existsBySubjectGroupId only sees currently active grants); its history
    // must survive that deletion.
    applyChangelog018();
    UUID library = insertLibrary(UUID.randomUUID());
    UUID group = insertGroup(UUID.randomUUID());
    insertGrantHistory(library, "GROUP", null, group, "VIEWER", "GRANTED");
    assertThat(grantHistoryCountFor(library)).isEqualTo(1);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM groups WHERE id = '" + group + "'");
    }

    assertThat(grantHistoryCountFor(library)).isEqualTo(1);
  }

  // ---------------------------------------------------------------------------------------
  // group_membership_history
  // ---------------------------------------------------------------------------------------

  @Test
  void chkGroupMembershipHistoryCauseRejectsAnUnknownCause() throws Exception {
    applyChangelog018();
    UUID group = insertGroup(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());

    assertThatThrownBy(() -> insertMembershipHistory(group, user, "REJECTED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_group_membership_history_cause");
  }

  @Test
  void chkGroupMembershipHistoryCauseAcceptsBackfill() throws Exception {
    applyChangelog018();
    UUID group = insertGroup(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());

    insertMembershipHistory(group, user, "BACKFILL");

    assertThat(membershipHistoryCountFor(group)).isEqualTo(1);
  }

  @Test
  void uniqueIndexRejectsASecondOpenIntervalForTheSameGroupAndUser() throws Exception {
    applyChangelog018();
    UUID group = insertGroup(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());

    insertMembershipHistory(group, user, "ADDED");
    assertThatThrownBy(() -> insertMembershipHistory(group, user, "DIRECTORY_SYNC_ADDED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_group_membership_history_open");
  }

  @Test
  void deletingTheSubjectUserIsBlockedWhileTheirMembershipHistoryExists() throws Exception {
    // Code review of #238, finding 4: an earlier version of this migration had user_id ON DELETE
    // CASCADE, contradicting docs/features/security-and-compliance.md ("die Historie selbst
    // bleibt unveraendert bestehen") and inconsistent with asset_grant_history.subject_user_id
    // (RESTRICT). Both subject columns are RESTRICT alike now.
    applyChangelog018();
    UUID group = insertGroup(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());
    insertMembershipHistory(group, user, "ADDED");
    assertThat(membershipHistoryCountFor(group)).isEqualTo(1);

    try (Statement statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.execute("DELETE FROM users WHERE id = '" + user + "'"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("fk_group_membership_history_user");
    }
    assertThat(membershipHistoryCountFor(group)).isEqualTo(1);
  }

  @Test
  void deletingAGroupLeavesItsMembershipHistoryIntact() throws Exception {
    // Code review of #238, finding 3: group_id carries no foreign key at all - deleting a group
    // (GroupService#deleteGroup, blocked only while it still owns an asset or holds a grant, not
    // while it merely has historised memberships) must never take that history down with it.
    applyChangelog018();
    UUID group = insertGroup(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());
    insertMembershipHistory(group, user, "REMOVED");
    assertThat(membershipHistoryCountFor(group)).isEqualTo(1);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM groups WHERE id = '" + group + "'");
    }

    assertThat(membershipHistoryCountFor(group)).isEqualTo(1);
  }

  // ---------------------------------------------------------------------------------------
  // library_visibility_history
  // ---------------------------------------------------------------------------------------

  @Test
  void chkLibraryVisibilityHistoryValuesRejectUnknownVisibilityAndCause() throws Exception {
    applyChangelog018();
    UUID library = insertLibrary(UUID.randomUUID());

    assertThatThrownBy(() -> insertVisibilityHistory(library, "SECRET", false, "CREATED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_library_visibility_history_visibility");
    assertThatThrownBy(() -> insertVisibilityHistory(library, "PRIVATE", false, "REJECTED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_library_visibility_history_cause");
  }

  @Test
  void chkLibraryVisibilityHistoryCauseAcceptsBackfill() throws Exception {
    applyChangelog018();
    UUID library = insertLibrary(UUID.randomUUID());

    insertVisibilityHistory(library, "PRIVATE", false, "BACKFILL");

    assertThat(visibilityHistoryCountFor(library)).isEqualTo(1);
  }

  @Test
  void uniqueIndexRejectsASecondOpenIntervalForTheSameLibrary() throws Exception {
    applyChangelog018();
    UUID library = insertLibrary(UUID.randomUUID());

    insertVisibilityHistory(library, "PRIVATE", false, "CREATED");
    assertThatThrownBy(
            () -> insertVisibilityHistory(library, "ORGANIZATION", true, "VISIBILITY_CHANGED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_library_visibility_history_open");
  }

  @Test
  void deletingALibraryLeavesItsVisibilityHistoryIntact() throws Exception {
    // Code review of #238, finding 3.
    applyChangelog018();
    UUID library = insertLibrary(UUID.randomUUID());
    insertVisibilityHistory(library, "PRIVATE", false, "CREATED");
    assertThat(visibilityHistoryCountFor(library)).isEqualTo(1);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM knowledge_libraries WHERE id = '" + library + "'");
    }

    assertThat(visibilityHistoryCountFor(library)).isEqualTo(1);
  }

  // ---------------------------------------------------------------------------------------
  // 018-backfill-permission-history
  // ---------------------------------------------------------------------------------------

  @Test
  void backfillWritesAnOpenIntervalForEveryExistingGrantWithItsCreatedAtAndGrantedByUser()
      throws Exception {
    UUID library = insertLibrary(UUID.randomUUID());
    UUID subject = insertUser(UUID.randomUUID());
    UUID grantedBy = insertUser(UUID.randomUUID());
    UUID grantId = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
              + " subject_user_id, role, granted_by_user_id, created_at, updated_at) VALUES ('"
              + grantId
              + "', '"
              + library
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'USER', '"
              + subject
              + "', 'VIEWER', '"
              + grantedBy
              + "', '2026-01-15T09:00:00Z', now())");
    }

    applyChangelog018();

    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT cause, actor_user_id, valid_from, valid_to FROM asset_grant_history"
                    + " WHERE library_id = '"
                    + library
                    + "' AND subject_user_id = '"
                    + subject
                    + "'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("cause")).isEqualTo("BACKFILL");
      assertThat(rs.getObject("actor_user_id")).isEqualTo(grantedBy);
      assertThat(rs.getTimestamp("valid_from").toInstant())
          .isEqualTo(Instant.parse("2026-01-15T09:00:00Z"));
      assertThat(rs.getTimestamp("valid_to")).isNull();
      assertThat(rs.next()).isFalse();
    }
  }

  @Test
  void backfillWritesAnOpenIntervalForEveryExistingMembershipWithNoActor() throws Exception {
    UUID group = insertGroup(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO group_memberships (id, user_id, group_id, organization_id, created_at)"
              + " VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + user
              + "', '"
              + group
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', '2026-02-01T00:00:00Z')");
    }

    applyChangelog018();

    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT cause, actor_user_id, valid_to FROM group_membership_history"
                    + " WHERE group_id = '"
                    + group
                    + "' AND user_id = '"
                    + user
                    + "'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("cause")).isEqualTo("BACKFILL");
      assertThat(rs.getObject("actor_user_id")).isNull();
      assertThat(rs.getTimestamp("valid_to")).isNull();
    }
  }

  @Test
  void backfillWritesAnOpenIntervalForEveryExistingLibraryWithOwnerAsActorOnlyForUserOwned()
      throws Exception {
    UUID userOwned = insertLibrary(UUID.randomUUID());
    UUID owner = insertUser(UUID.randomUUID());
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "UPDATE knowledge_libraries SET owner_type = 'USER', owner_user_id = '"
              + owner
              + "' WHERE id = '"
              + userOwned
              + "'");
    }
    UUID systemOwned = insertLibrary(UUID.randomUUID());

    applyChangelog018();

    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT actor_user_id, cause FROM library_visibility_history WHERE library_id = '"
                    + userOwned
                    + "'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getObject("actor_user_id")).isEqualTo(owner);
      assertThat(rs.getString("cause")).isEqualTo("BACKFILL");
    }
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT actor_user_id FROM library_visibility_history WHERE library_id = '"
                    + systemOwned
                    + "'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getObject("actor_user_id")).isNull();
    }
  }

  // ---------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------

  private void applyChangelog018() throws Exception {
    applyChangelog(connection, "db/changelog/changes/018-permission-history.yaml");
  }

  private UUID insertUser(UUID id) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO users (id, subject, issuer, system_role, organization_id, created_at) "
              + "VALUES ('"
              + id
              + "', '"
              + id
              + "', 'test-issuer', 'USER', '"
              + SEEDED_ORGANIZATION_ID
              + "', now())");
    }
    return id;
  }

  private UUID insertGroup(UUID id) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO groups (id, organization_id, kind, name, created_at, updated_at) "
              + "VALUES ('"
              + id
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'AD_HOC', 'Gruppe "
              + id
              + "', now(), now())");
    }
    return id;
  }

  private UUID insertLibrary(UUID id) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_libraries "
              + "(id, organization_id, name, owner_type, owner_user_id, owner_group_id,"
              + " visibility, listed, personal, created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'Bibliothek "
              + id
              + "', 'SYSTEM', NULL, NULL, 'PRIVATE', false, false, now(), now())");
    }
    return id;
  }

  private void insertGrantHistory(
      UUID libraryId,
      String subjectType,
      UUID subjectUserId,
      UUID subjectGroupId,
      String role,
      String cause)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO asset_grant_history (id, library_id, organization_id, subject_type,"
              + " subject_user_id, subject_group_id, role, cause, valid_from, created_at) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + libraryId
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', '"
              + subjectType
              + "', "
              + (subjectUserId == null ? "NULL" : "'" + subjectUserId + "'")
              + ", "
              + (subjectGroupId == null ? "NULL" : "'" + subjectGroupId + "'")
              + ", '"
              + role
              + "', '"
              + cause
              + "', now(), now())");
    }
  }

  private void insertMembershipHistory(UUID groupId, UUID userId, String cause)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO group_membership_history (id, group_id, organization_id, user_id, cause,"
              + " valid_from, created_at) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + groupId
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', '"
              + userId
              + "', '"
              + cause
              + "', now(), now())");
    }
  }

  private void insertVisibilityHistory(
      UUID libraryId, String visibility, boolean listed, String cause) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO library_visibility_history (id, library_id, organization_id, visibility,"
              + " listed, cause, valid_from, created_at) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + libraryId
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', '"
              + visibility
              + "', "
              + listed
              + ", '"
              + cause
              + "', now(), now())");
    }
  }

  private long grantHistoryCountFor(UUID libraryId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT count(*) FROM asset_grant_history WHERE library_id = '"
                    + libraryId
                    + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private long membershipHistoryCountFor(UUID groupId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT count(*) FROM group_membership_history WHERE group_id = '"
                    + groupId
                    + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private long visibilityHistoryCountFor(UUID libraryId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT count(*) FROM library_visibility_history WHERE library_id = '"
                    + libraryId
                    + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
