package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Applies Liquibase changelog 018 in isolation against a database built from the real, versioned
 * changelog through changeSet 016 - the same pattern as {@code
 * Migration016VectorStoreLibraryMetadataTest}, with {@code test-master-through-016.yaml} as the
 * pre-migration fixture (changeSet 017 belongs to a different, parallel issue and is deliberately
 * not part of this fixture). {@code connection.setAutoCommit(true)} is called after every {@code
 * liquibase.update(...)} call, and the public schema is dropped and recreated between test methods,
 * per the package Javadoc's mandatory teardown pattern.
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration018PermissionHistoryTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  private Connection connection;
  private Database database;

  @BeforeEach
  void setUp() throws Exception {
    connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    database =
        DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(new JdbcConnection(connection));

    Liquibase liquibase =
        new Liquibase(
            "db/changelog/test-master-through-016.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());
    connection.setAutoCommit(true);

    applyChangelog018();
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.setAutoCommit(true);
    try (Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA public CASCADE");
      statement.execute("CREATE SCHEMA public");
    }
    connection.close();
  }

  // ---------------------------------------------------------------------------------------
  // asset_grant_history
  // ---------------------------------------------------------------------------------------

  @Test
  void chkAssetGrantHistorySubjectRejectsEveryMismatchBetweenSubjectTypeAndSubjectColumns()
      throws Exception {
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
    UUID library = insertLibrary(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());

    assertThatThrownBy(() -> insertGrantHistory(library, "USER", user, null, "VIEWER", "REJECTED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_asset_grant_history_cause");
  }

  @Test
  void chkAssetGrantHistoryRoleRejectsARoleNameFromTheDisjointSpaceRoleSystem() throws Exception {
    UUID library = insertLibrary(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());

    assertThatThrownBy(() -> insertGrantHistory(library, "USER", user, null, "ADMIN", "GRANTED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_asset_grant_history_role");
  }

  @Test
  void uniqueIndexRejectsASecondOpenIntervalForTheSameLibraryAndSubject() throws Exception {
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
  void deletingALibraryCascadesToItsGrantHistory() throws Exception {
    UUID library = insertLibrary(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());
    insertGrantHistory(library, "USER", user, null, "OWNER", "GRANTED");
    assertThat(grantHistoryCountFor(library)).isEqualTo(1);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM knowledge_libraries WHERE id = '" + library + "'");
    }

    assertThat(grantHistoryCountFor(library)).isZero();
  }

  // ---------------------------------------------------------------------------------------
  // group_membership_history
  // ---------------------------------------------------------------------------------------

  @Test
  void chkGroupMembershipHistoryCauseRejectsAnUnknownCause() throws Exception {
    UUID group = insertGroup(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());

    assertThatThrownBy(() -> insertMembershipHistory(group, user, "REJECTED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_group_membership_history_cause");
  }

  @Test
  void uniqueIndexRejectsASecondOpenIntervalForTheSameGroupAndUser() throws Exception {
    UUID group = insertGroup(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());

    insertMembershipHistory(group, user, "ADDED");
    assertThatThrownBy(() -> insertMembershipHistory(group, user, "DIRECTORY_SYNC_ADDED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_group_membership_history_open");
  }

  @Test
  void deletingAUserCascadesToTheirMembershipHistory() throws Exception {
    UUID group = insertGroup(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());
    insertMembershipHistory(group, user, "ADDED");
    assertThat(membershipHistoryCountFor(group)).isEqualTo(1);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM users WHERE id = '" + user + "'");
    }

    assertThat(membershipHistoryCountFor(group)).isZero();
  }

  // ---------------------------------------------------------------------------------------
  // library_visibility_history
  // ---------------------------------------------------------------------------------------

  @Test
  void chkLibraryVisibilityHistoryValuesRejectUnknownVisibilityAndCause() throws Exception {
    UUID library = insertLibrary(UUID.randomUUID());

    assertThatThrownBy(() -> insertVisibilityHistory(library, "SECRET", false, "CREATED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_library_visibility_history_visibility");
    assertThatThrownBy(() -> insertVisibilityHistory(library, "PRIVATE", false, "REJECTED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_library_visibility_history_cause");
  }

  @Test
  void uniqueIndexRejectsASecondOpenIntervalForTheSameLibrary() throws Exception {
    UUID library = insertLibrary(UUID.randomUUID());

    insertVisibilityHistory(library, "PRIVATE", false, "CREATED");
    assertThatThrownBy(
            () -> insertVisibilityHistory(library, "ORGANIZATION", true, "VISIBILITY_CHANGED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_library_visibility_history_open");
  }

  @Test
  void deletingALibraryCascadesToItsVisibilityHistory() throws Exception {
    UUID library = insertLibrary(UUID.randomUUID());
    insertVisibilityHistory(library, "PRIVATE", false, "CREATED");
    assertThat(visibilityHistoryCountFor(library)).isEqualTo(1);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM knowledge_libraries WHERE id = '" + library + "'");
    }

    assertThat(visibilityHistoryCountFor(library)).isZero();
  }

  // ---------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------

  private void applyChangelog018() throws Exception {
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/018-permission-history.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());
    connection.setAutoCommit(true);
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
