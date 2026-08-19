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
 * Applies Liquibase changelog 011 in isolation against a database built from the real, versioned
 * changelog through changeSet 010 - the same pattern as {@code Migration010SpaceUniquenessTest},
 * with {@code test-master-through-010.yaml} as the pre-migration fixture, now built once per class
 * into a template database and cloned per test method ({@link AbstractMigrationTest}).
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration011DirectorySyncTest extends AbstractMigrationTest {

  private static final String ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-010.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    insertOrganization(ORGANIZATION_ID);
  }

  @AfterEach
  void tearDown() throws SQLException {
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
    applyChangelog(connection, "db/changelog/changes/011-directory-sync.yaml");
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
