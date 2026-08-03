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
 * Applies Liquibase changelog 013 in isolation against a database built from the real, versioned
 * changelog through changeSet 012 - the same pattern as {@code Migration012KnowledgeLibrariesTest},
 * with {@code test-master-through-012.yaml} as the pre-migration fixture. {@code
 * connection.setAutoCommit(true)} is called after every {@code liquibase.update(...)} call, and the
 * public schema is dropped and recreated between test methods, per the package Javadoc's mandatory
 * teardown pattern.
 *
 * <p>{@link
 * #backfillGrantsOwnerAccessForExistingUserAndGroupOwnedLibrariesButNotTheSystemLibrary()} is the
 * mechanism-interaction test: it combines the backfill changeSet with both owner column variants
 * (USER and GROUP) and the SYSTEM library's deliberate exemption in a single run, not any of the
 * three in isolation - a backfill that only handles USER owners, for instance, would lock every
 * existing group-owned library's members out the moment #202's LibraryAccessService replaces the
 * coarse #201 canRead/canManage that this migration accompanies.
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration013AssetGrantsTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";
  private static final String SYSTEM_LIBRARY_ID = "00000000-0000-0000-0000-000000000002";

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
            "db/changelog/test-master-through-012.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());
    connection.setAutoCommit(true);
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

  @Test
  void backfillGrantsOwnerAccessForExistingUserAndGroupOwnedLibrariesButNotTheSystemLibrary()
      throws Exception {
    UUID user = insertUser(UUID.randomUUID());
    UUID group = insertGroup(UUID.randomUUID());
    UUID userOwnedLibrary = insertLibrary(UUID.randomUUID(), "USER", user, null);
    UUID groupOwnedLibrary = insertLibrary(UUID.randomUUID(), "GROUP", null, group);

    applyChangelog013();

    assertThat(grantCountFor(userOwnedLibrary)).isEqualTo(1);
    assertThat(grantRole(userOwnedLibrary, "USER", user.toString())).isEqualTo("OWNER");

    assertThat(grantCountFor(groupOwnedLibrary)).isEqualTo(1);
    assertThat(grantRole(groupOwnedLibrary, "GROUP", group.toString())).isEqualTo("OWNER");

    // The SYSTEM library stays fail-closed to system admins with no grant at all - backfilling one
    // would open a hole in the exact invariant KnowledgeLibrary.SYSTEM_LIBRARY_ID exists to close.
    assertThat(grantCountFor(UUID.fromString(SYSTEM_LIBRARY_ID))).isZero();
  }

  @Test
  void chkAssetGrantsSubjectRejectsEveryMismatchBetweenSubjectTypeAndSubjectColumns()
      throws Exception {
    applyChangelog013();
    UUID library = insertLibrary(UUID.randomUUID(), "SYSTEM", null, null);
    UUID user = insertUser(UUID.randomUUID());
    UUID group = insertGroup(UUID.randomUUID());

    assertThatThrownBy(() -> insertGrant(library, "USER", null, null, "OWNER"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_asset_grants_subject");
    assertThatThrownBy(() -> insertGrant(library, "USER", null, group, "OWNER"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_asset_grants_subject");
    assertThatThrownBy(() -> insertGrant(library, "GROUP", null, null, "OWNER"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_asset_grants_subject");
    assertThatThrownBy(() -> insertGrant(library, "GROUP", user, null, "OWNER"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_asset_grants_subject");
  }

  @Test
  void chkAssetGrantsRoleRejectsARoleNameFromTheDisjointSpaceRoleSystem() throws Exception {
    applyChangelog013();
    UUID library = insertLibrary(UUID.randomUUID(), "SYSTEM", null, null);
    UUID user = insertUser(UUID.randomUUID());

    // #202 acceptance criteria: no role name exists simultaneously in SpaceRole and the asset role
    // enum - ADMIN is a SpaceRole, never a valid AssetRole.
    assertThatThrownBy(() -> insertGrant(library, "USER", user, null, "ADMIN"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_asset_grants_role");
  }

  @Test
  void foreignKeysRejectASubjectThatDoesNotExist() throws Exception {
    applyChangelog013();
    UUID library = insertLibrary(UUID.randomUUID(), "SYSTEM", null, null);

    assertThatThrownBy(() -> insertGrant(library, "USER", UUID.randomUUID(), null, "VIEWER"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_asset_grants_subject_user");
    assertThatThrownBy(() -> insertGrant(library, "GROUP", null, UUID.randomUUID(), "VIEWER"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_asset_grants_subject_group_organization");
  }

  @Test
  void compositeForeignKeyRejectsAGrantPointingAtALibraryFromAnotherOrganization()
      throws Exception {
    applyChangelog013();
    UUID otherOrganization = UUID.randomUUID();
    insertOrganization(otherOrganization);
    UUID otherOrgUser = insertUser(UUID.randomUUID(), otherOrganization.toString());
    UUID libraryInOtherOrganization =
        insertLibrary(UUID.randomUUID(), "USER", otherOrgUser, null, otherOrganization.toString());

    try (Statement statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.execute(
                      "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
                          + " subject_user_id, role, created_at, updated_at) VALUES ('"
                          + UUID.randomUUID()
                          + "', '"
                          + libraryInOtherOrganization
                          + "', '"
                          + SEEDED_ORGANIZATION_ID
                          + "', 'USER', '"
                          + otherOrgUser
                          + "', 'OWNER', now(), now())"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("fk_asset_grants_library_organization");
    }
  }

  @Test
  void partialUniqueIndexesRejectASecondGrantForTheSameSubjectOnTheSameLibrary() throws Exception {
    applyChangelog013();
    UUID library = insertLibrary(UUID.randomUUID(), "SYSTEM", null, null);
    UUID user = insertUser(UUID.randomUUID());
    UUID group = insertGroup(UUID.randomUUID());

    insertGrant(library, "USER", user, null, "VIEWER");
    assertThatThrownBy(() -> insertGrant(library, "USER", user, null, "MANAGER"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_asset_grants_user_subject");

    insertGrant(library, "GROUP", null, group, "VIEWER");
    assertThatThrownBy(() -> insertGrant(library, "GROUP", null, group, "MANAGER"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_asset_grants_group_subject");

    // The same user/group may still hold grants on any number of other libraries.
    UUID otherLibrary = insertLibrary(UUID.randomUUID(), "SYSTEM", null, null);
    insertGrant(otherLibrary, "USER", user, null, "VIEWER");
  }

  @Test
  void deletingALibraryCascadesToItsGrants() throws Exception {
    applyChangelog013();
    UUID library = insertLibrary(UUID.randomUUID(), "SYSTEM", null, null);
    UUID user = insertUser(UUID.randomUUID());
    insertGrant(library, "USER", user, null, "OWNER");
    assertThat(grantCountFor(library)).isEqualTo(1);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM knowledge_libraries WHERE id = '" + library + "'");
    }

    assertThat(grantCountFor(library)).isZero();
  }

  private void applyChangelog013() throws Exception {
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/013-asset-grants.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());
    connection.setAutoCommit(true);
  }

  private void insertOrganization(UUID id) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO organizations (id, name, created_at) VALUES ('"
              + id
              + "', 'Org "
              + id
              + "', now()) ON CONFLICT (id) DO NOTHING");
    }
  }

  private UUID insertUser(UUID id) throws SQLException {
    return insertUser(id, SEEDED_ORGANIZATION_ID);
  }

  private UUID insertUser(UUID id, String organizationId) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO users (id, subject, issuer, system_role, organization_id, created_at) "
              + "VALUES ('"
              + id
              + "', '"
              + id
              + "', 'test-issuer', 'USER', '"
              + organizationId
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

  private UUID insertLibrary(UUID id, String ownerType, UUID ownerUserId, UUID ownerGroupId)
      throws SQLException {
    return insertLibrary(id, ownerType, ownerUserId, ownerGroupId, SEEDED_ORGANIZATION_ID);
  }

  private UUID insertLibrary(
      UUID id, String ownerType, UUID ownerUserId, UUID ownerGroupId, String organizationId)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_libraries "
              + "(id, organization_id, name, owner_type, owner_user_id, owner_group_id,"
              + " visibility, listed, personal, created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + organizationId
              + "', 'Bibliothek "
              + id
              + "', '"
              + ownerType
              + "', "
              + (ownerUserId == null ? "NULL" : "'" + ownerUserId + "'")
              + ", "
              + (ownerGroupId == null ? "NULL" : "'" + ownerGroupId + "'")
              + ", 'PRIVATE', false, false, now(), now())");
    }
    return id;
  }

  private void insertGrant(
      UUID libraryId, String subjectType, UUID subjectUserId, UUID subjectGroupId, String role)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
              + " subject_user_id, subject_group_id, role, created_at, updated_at) VALUES ('"
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
              + "', now(), now())");
    }
  }

  private long grantCountFor(UUID libraryId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT count(*) FROM asset_grants WHERE library_id = '" + libraryId + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private String grantRole(UUID libraryId, String subjectType, String subjectId)
      throws SQLException {
    String subjectColumn = subjectType.equals("USER") ? "subject_user_id" : "subject_group_id";
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT role FROM asset_grants WHERE library_id = '"
                    + libraryId
                    + "' AND subject_type = '"
                    + subjectType
                    + "' AND "
                    + subjectColumn
                    + " = '"
                    + subjectId
                    + "'")) {
      rs.next();
      return rs.getString(1);
    }
  }
}
