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
 * Applies Liquibase changelog 009 in isolation against a database built from the real, versioned
 * changelog through changeSet 008 - the same pattern as {@link
 * Migration008RenameWorkspaceToSpaceTest}, with a {@code test-master-through-008.yaml} fixture
 * instead of 007.
 *
 * <p>Unlike 008, this changelog introduces new tables rather than migrating existing data, so there
 * is nothing to seed beforehand. What still needs a real database is the composite foreign key
 * {@code fk_group_memberships_group_organization}, which is the mechanism that keeps a group
 * membership from ever crossing the organization boundary (see #200's acceptance criteria and
 * {@code SpaceService#requireUserInOrganization} for the analogous application-level check on
 * spaces). That constraint cannot be exercised by {@code GroupServiceIntegrationTest}, which runs
 * with {@code spring.liquibase.enabled=false} and a Hibernate-generated schema that never executes
 * this changeSet.
 *
 * <p>The container is shared across both test methods (declared {@code static}), so each test drops
 * and recreates the {@code public} schema in {@link #tearDown()} - without that, the second test
 * would run against a database that already has changelog 009 applied and Liquibase would skip it
 * as already-run, silently passing even if the changeSet were broken.
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration009CreateGroupsTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  private static final String ORGANIZATION_A = "00000000-0000-0000-0000-000000000001";
  private static final String ORGANIZATION_B = "00000000-0000-0000-0000-000000000002";

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

    // Apply everything up to (and including) changeSet 008 - the schema exactly as it existed
    // immediately before this migration - via the real, versioned changelog files.
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/test-master-through-008.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());

    insertOrganization(ORGANIZATION_A);
  }

  @AfterEach
  void tearDown() throws SQLException {
    if (connection != null && !connection.isClosed()) {
      // Reset for the next test method: this container is shared (static) across both tests in
      // this class, and Liquibase would otherwise see changeSet 009 as already applied (recorded
      // in DATABASECHANGELOG) and silently skip it on the second run.
      // Liquibase's JdbcConnection disables autocommit, so a test that triggers a constraint
      // violation (see rejectsAMembershipRowWhoseGroupBelongsToAnotherOrganization) leaves the
      // connection's transaction aborted; rollback() is always safe to call, even with nothing
      // pending, and clears that state before the DROP SCHEMA below.
      connection.rollback();
      try (Statement statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA public CASCADE");
        statement.execute("CREATE SCHEMA public");
      }
      connection.close();
    }
  }

  @Test
  void createsGroupsAndMembershipsScopedToOrganization() throws Exception {
    applyChangelog009();

    UUID userOne = UUID.randomUUID();
    UUID userTwo = UUID.randomUUID();
    insertUser(userOne, ORGANIZATION_A);
    insertUser(userTwo, ORGANIZATION_A);

    UUID adHocGroup = UUID.randomUUID();
    insertGroup(adHocGroup, ORGANIZATION_A, "AD_HOC", "Projektbeteiligte Phoenix", null, null);
    UUID orgUnitGroup = UUID.randomUUID();
    insertGroup(orgUnitGroup, ORGANIZATION_A, "ORG_UNIT", "Referat 50", "directory-guid-1", null);

    insertMembership(adHocGroup, userOne, ORGANIZATION_A);
    insertMembership(adHocGroup, userTwo, ORGANIZATION_A);
    insertMembership(orgUnitGroup, userOne, ORGANIZATION_A);

    assertThat(countRows("groups")).isEqualTo(2);
    assertThat(countRows("group_memberships")).isEqualTo(3);
    assertThat(columnValue("groups", "kind", "id", adHocGroup)).isEqualTo("AD_HOC");
    assertThat(columnValue("groups", "kind", "id", orgUnitGroup)).isEqualTo("ORG_UNIT");
    assertThat(columnValue("groups", "external_id", "id", orgUnitGroup))
        .isEqualTo("directory-guid-1");
  }

  @Test
  void rejectsAMembershipRowWhoseGroupBelongsToAnotherOrganization() throws Exception {
    applyChangelog009();
    insertOrganization(ORGANIZATION_B);

    UUID user = UUID.randomUUID();
    insertUser(user, ORGANIZATION_A);
    UUID groupInOrganizationA = UUID.randomUUID();
    insertGroup(groupInOrganizationA, ORGANIZATION_A, "AD_HOC", "Team A", null, null);

    // The composite foreign key fk_group_memberships_group_organization references
    // groups(id, organization_id) - a membership row naming this group but organization B must
    // violate it, because no such (id, organization_id) pair exists in groups.
    assertThatThrownBy(() -> insertMembership(groupInOrganizationA, user, ORGANIZATION_B))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_group_memberships_group_organization");
  }

  private void applyChangelog009() throws Exception {
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/009-create-groups.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());
  }

  private void insertOrganization(String id) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO organizations (id, name, created_at) VALUES ('"
              + id
              + "', 'Org "
              + id
              + "', now()) ON CONFLICT (id) DO NOTHING");
    }
  }

  private void insertUser(UUID id, String organizationId) throws SQLException {
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
  }

  private void insertGroup(
      UUID id, String organizationId, String kind, String name, String externalId, UUID parentId)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO groups (id, organization_id, kind, name, external_id, parent_group_id,"
              + " created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + organizationId
              + "', '"
              + kind
              + "', '"
              + name
              + "', "
              + (externalId == null ? "NULL" : "'" + externalId + "'")
              + ", "
              + (parentId == null ? "NULL" : "'" + parentId + "'")
              + ", now(), now())");
    }
  }

  private void insertMembership(UUID groupId, UUID userId, String organizationId)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO group_memberships (id, user_id, group_id, organization_id, created_at) "
              + "VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + userId
              + "', '"
              + groupId
              + "', '"
              + organizationId
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
}
