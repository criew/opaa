package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Applies Liquibase changelog 014 in isolation against a database built from the real, versioned
 * changelog through changeSet 013 - the same pattern as {@code Migration013AssetGrantsTest}, with
 * {@code test-master-through-013.yaml} as the pre-migration fixture. {@code
 * connection.setAutoCommit(true)} is called after every {@code liquibase.update(...)} call, and the
 * public schema is dropped and recreated between test methods, per the package Javadoc's mandatory
 * teardown pattern.
 *
 * <p>The point of these tests is that changelog 014 runs against <em>live data</em>: an existing
 * {@code USER} grant must survive as {@code VIEWER} rather than be deleted or block the narrowing
 * ALTER. Ordering the two changeSets the other way round - constraint first, data second - would
 * fail on any database that already holds a USER grant, which is precisely the case the migration
 * exists for.
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration014DropAssetRoleUserTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-013.yaml";
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

  @Test
  void anExistingUserGrantIsPromotedToViewerRatherThanDeleted() throws Exception {
    UUID library = insertLibrary(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());
    insertGrant(library, user, "USER");

    applyChangelog014();

    // Promoting rather than deleting is the deliberate choice: a holder of USER was meant to be
    // able to use the asset, and revoking that on migration would silently take working access
    // away. VIEWER is the smallest rank that preserves it.
    assertThat(grantCountFor(library)).isEqualTo(1);
    assertThat(roleOfGrantFor(library, user)).isEqualTo("VIEWER");
  }

  @Test
  void aGrantThatAlreadyHeldAHigherRankIsLeftUntouched() throws Exception {
    UUID library = insertLibrary(UUID.randomUUID());
    UUID manager = insertUser(UUID.randomUUID());
    insertGrant(library, manager, "MANAGER");

    applyChangelog014();

    assertThat(roleOfGrantFor(library, manager)).isEqualTo("MANAGER");
  }

  @Test
  void theNarrowedCheckConstraintRejectsUserAsARole() throws Exception {
    applyChangelog014();
    UUID library = insertLibrary(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());

    assertThatThrownBy(() -> insertGrant(library, user, "USER"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_asset_grants_role");
  }

  @Test
  void theFourRemainingRolesStayAccepted() throws Exception {
    applyChangelog014();
    UUID library = insertLibrary(UUID.randomUUID());

    for (String role : new String[] {"VIEWER", "EDITOR", "MANAGER", "OWNER"}) {
      insertGrant(library, insertUser(UUID.randomUUID()), role);
    }

    assertThat(grantCountFor(library)).isEqualTo(4);
  }

  @Test
  void subjectTypeUserRemainsValidBecauseItIsADifferentEnum() throws Exception {
    applyChangelog014();
    UUID library = insertLibrary(UUID.randomUUID());
    UUID user = insertUser(UUID.randomUUID());

    // chk_asset_grants_subject_type keeps its own 'USER' value - that is PermissionSubjectType on
    // a different column. Narrowing the role check must not touch it; this test fails loudly if a
    // future edit conflates the two.
    insertGrant(library, user, "VIEWER");

    assertThat(subjectTypeOfGrantFor(library, user)).isEqualTo("USER");
  }

  private void applyChangelog014() throws Exception {
    applyChangelog(connection, "db/changelog/changes/014-drop-asset-role-user.yaml");
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

  private void insertGrant(UUID libraryId, UUID userId, String role) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
              + " subject_user_id, role, created_at, updated_at) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + libraryId
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'USER', '"
              + userId
              + "', '"
              + role
              + "', now(), now())");
    }
  }

  private int grantCountFor(UUID libraryId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM asset_grants WHERE library_id = '" + libraryId + "'")) {
      result.next();
      return result.getInt(1);
    }
  }

  private String roleOfGrantFor(UUID libraryId, UUID userId) throws SQLException {
    return singleColumnOfGrant("role", libraryId, userId);
  }

  private String subjectTypeOfGrantFor(UUID libraryId, UUID userId) throws SQLException {
    return singleColumnOfGrant("subject_type", libraryId, userId);
  }

  private String singleColumnOfGrant(String column, UUID libraryId, UUID userId)
      throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT "
                    + column
                    + " FROM asset_grants WHERE library_id = '"
                    + libraryId
                    + "' AND subject_user_id = '"
                    + userId
                    + "'")) {
      return result.next() ? result.getString(1) : null;
    }
  }
}
