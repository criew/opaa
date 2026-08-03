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
 * Applies Liquibase changelog 011 in isolation against a database built from the real, versioned
 * changelog through changeSet 010 - the same pattern as {@code Migration010SpaceUniquenessTest},
 * with {@code test-master-through-010.yaml} as the pre-migration fixture. Uses {@code
 * connection.setAutoCommit(true)} after every {@code liquibase.update(...)} call rather than the
 * older {@code connection.rollback()} teardown some earlier migration tests use - the pattern #283
 * established as the single one going forward, because it addresses the actual cause (Liquibase
 * leaves auto-commit disabled) instead of working around the symptom on teardown.
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration011DirectorySyncTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  private static final String ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

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
            "db/changelog/test-master-through-010.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());
    connection.setAutoCommit(true);

    insertOrganization(ORGANIZATION_ID);
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
  void addsDissolvedColumnsToGroupsDefaultingToNotDissolved() throws Exception {
    applyChangelog011();

    UUID orgUnit = UUID.randomUUID();
    insertGroup(orgUnit, "ORG_UNIT", "Referat 50", "directory-guid-1");

    assertThat(columnValue("groups", "dissolved", orgUnit)).isEqualTo("f");
    assertThat(columnValueOrNull("groups", "dissolved_at", orgUnit)).isNull();
  }

  @Test
  void createsDirectorySyncStatusTableScopedToOneRowPerOrganization() throws Exception {
    applyChangelog011();

    UUID statusId = UUID.randomUUID();
    insertSyncStatus(statusId, ORGANIZATION_ID, "APPLIED");
    assertThat(countRows("directory_sync_status")).isEqualTo(1);

    // uk_directory_sync_status_organization must reject a second row for the same organization.
    assertThatThrownBy(() -> insertSyncStatus(UUID.randomUUID(), ORGANIZATION_ID, "DRY_RUN"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_directory_sync_status_organization");
  }

  private void applyChangelog011() throws Exception {
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/011-directory-sync.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());
    connection.setAutoCommit(true);
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

  private void insertGroup(UUID id, String kind, String name, String externalId)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO groups (id, organization_id, kind, name, external_id, created_at,"
              + " updated_at) VALUES ('"
              + id
              + "', '"
              + ORGANIZATION_ID
              + "', '"
              + kind
              + "', '"
              + name
              + "', '"
              + externalId
              + "', now(), now())");
    }
  }

  private void insertSyncStatus(UUID id, String organizationId, String outcome)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO directory_sync_status (id, organization_id, last_run_at, last_outcome) "
              + "VALUES ('"
              + id
              + "', '"
              + organizationId
              + "', now(), '"
              + outcome
              + "')");
    }
  }

  private long countRows(String table) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private String columnValue(String table, String column, UUID id) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT " + column + " FROM " + table + " WHERE id = '" + id + "'")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private String columnValueOrNull(String table, String column, UUID id) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT " + column + " FROM " + table + " WHERE id = '" + id + "'")) {
      rs.next();
      String value = rs.getString(1);
      return rs.wasNull() ? null : value;
    }
  }
}
