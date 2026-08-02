package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Applies Liquibase changelog 008 in isolation against a database pre-populated with legacy
 * workspace data covering all four historical roles and both historical workspace types - not
 * against an empty schema.
 *
 * <p>{@code SpaceServiceIntegrationTest} and {@code SpaceRepositoryTest} run the real, versioned
 * Liquibase changelog too (since #288) - but against an empty database at context startup, exactly
 * like {@code OpaaApplicationTests}. That proves changeset 008 is syntactically applicable, not
 * that a *data* migration survives non-empty legacy data. It is exactly this gap that let a
 * check-constraint ordering bug in {@code 008-remap-space-membership-roles} pass CI: the changeSet
 * failed on any database that had at least one VIEWER or EDITOR membership row.
 *
 * <p>This test closes that gap and is meant as a reusable pattern for future data migrations (see
 * #237, #238): apply the real, versioned changelog up to the changeSet immediately preceding the
 * new one (via a small fixture changelog such as {@code test-master-through-007.yaml}), seed
 * representative legacy rows directly through JDBC, apply only the new changelog file, and then
 * assert on row counts and value mapping.
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration008RenameWorkspaceToSpaceTest {

  // Uses the pgvector image (not plain postgres) because changelog 001, which this test also
  // exercises as part of building the pre-008 schema, enables the pgvector extension - a binary
  // that plain postgres images do not ship. This is unrelated to the bug this test guards
  // against, which is a pure constraint-ordering issue reproducible on any Postgres.
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

    // Apply everything up to (and including) changeSet 007 - the schema exactly as it existed
    // immediately before this migration - via the real, versioned changelog files.
    // Deliberately not try-with-resources: Liquibase.close() also closes the underlying
    // Database/Connection, which this test still needs afterwards to seed data and to apply
    // changelog 008.
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/test-master-through-007.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());
  }

  @AfterEach
  void tearDown() throws SQLException {
    if (connection != null && !connection.isClosed()) {
      connection.close();
    }
  }

  @Test
  void migratesNonEmptyLegacyDataWithoutLoss() throws Exception {
    UUID ownerUser = UUID.randomUUID();
    UUID adminUser = UUID.randomUUID();
    UUID editorUser = UUID.randomUUID();
    UUID viewerUser = UUID.randomUUID();
    insertUser(ownerUser);
    insertUser(adminUser);
    insertUser(editorUser);
    insertUser(viewerUser);

    UUID personalWorkspace = UUID.randomUUID();
    UUID sharedWorkspace = UUID.randomUUID();
    insertWorkspace(personalWorkspace, "My Documents", "PERSONAL", ownerUser);
    insertWorkspace(sharedWorkspace, "Engineering", "SHARED", ownerUser);

    insertMembership(personalWorkspace, ownerUser, "OWNER");
    insertMembership(sharedWorkspace, ownerUser, "OWNER");
    insertMembership(sharedWorkspace, adminUser, "ADMIN");
    insertMembership(sharedWorkspace, editorUser, "EDITOR");
    insertMembership(sharedWorkspace, viewerUser, "VIEWER");

    // Apply changelog 008 in isolation, on top of the non-empty legacy schema seeded above.
    // Blocker 1 (reported against PR #254) failed exactly here with
    // "violates check constraint chk_workspace_memberships_role" whenever a VIEWER or EDITOR
    // row existed, because the old constraint was still active during the role UPDATE.
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/008-rename-workspace-to-space.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());

    assertThat(countRows("organizations")).isEqualTo(1);
    assertThat(countRows("spaces")).isEqualTo(2);
    assertThat(countRows("space_memberships")).isEqualTo(5);

    assertThat(columnValue("spaces", "kind", "id", personalWorkspace)).isEqualTo("PERSONAL");
    assertThat(columnValue("spaces", "kind", "id", sharedWorkspace)).isEqualTo("TEAM");

    assertThat(roleOf(sharedWorkspace, ownerUser)).isEqualTo("ADMIN");
    assertThat(roleOf(sharedWorkspace, adminUser)).isEqualTo("ADMIN");
    assertThat(roleOf(sharedWorkspace, editorUser)).isEqualTo("CURATOR");
    assertThat(roleOf(sharedWorkspace, viewerUser)).isEqualTo("MEMBER");

    // owner_id is untouched by the role remap - the space still knows who the real owner was,
    // even though their membership role is now the same ADMIN as any other admin.
    assertThat(columnValue("spaces", "owner_id", "id", sharedWorkspace))
        .isEqualTo(ownerUser.toString());

    assertThat(distinctOrganizationIds()).containsExactly(SEEDED_ORGANIZATION_ID);
  }

  private void insertUser(UUID id) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO users (id, subject, issuer, system_role, created_at) VALUES ('"
              + id
              + "', '"
              + id
              + "', 'test-issuer', 'USER', now())");
    }
  }

  private void insertWorkspace(UUID id, String name, String type, UUID ownerId)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO workspaces (id, name, type, owner_id, created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + name
              + "', '"
              + type
              + "', '"
              + ownerId
              + "', now(), now())");
    }
  }

  private void insertMembership(UUID workspaceId, UUID userId, String role) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO workspace_memberships (id, user_id, workspace_id, role, created_at) "
              + "VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + userId
              + "', '"
              + workspaceId
              + "', '"
              + role
              + "', now())");
    }
  }

  private long countRows(String table) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private String columnValue(String table, String column, String whereColumn, UUID whereValue)
      throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT "
                    + column
                    + " FROM "
                    + table
                    + " WHERE "
                    + whereColumn
                    + " = '"
                    + whereValue
                    + "'")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private String roleOf(UUID spaceId, UUID userId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT role FROM space_memberships WHERE space_id = '"
                    + spaceId
                    + "' AND user_id = '"
                    + userId
                    + "'")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private java.util.List<String> distinctOrganizationIds() throws SQLException {
    java.util.List<String> ids = new java.util.ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT DISTINCT organization_id FROM spaces "
                    + "UNION SELECT DISTINCT organization_id FROM space_memberships "
                    + "UNION SELECT DISTINCT organization_id FROM users")) {
      while (rs.next()) {
        ids.add(rs.getString(1));
      }
    }
    return ids;
  }
}
