package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/015-confluence-run-metrics.yaml} (#1141): the nullable metric
 * columns and the {@code incomplete} flag on {@code indexing_jobs}. Builds on 010, 013 and 014 because the
 * fixture rows use the CONFLUENCE type, the run mode and the WEBHOOK trigger they introduced.
 */
class Migration015ConfluenceRunMetricsTest extends AbstractMigrationTest {

  private static final String[] PREDECESSORS = {
    "db/changelog/changes/010-confluence-source-type.yaml",
    "db/changelog/changes/013-confluence-full-sync.yaml",
    "db/changelog/changes/014-confluence-webhooks.yaml"
  };
  private static final String CHANGELOG_PATH =
      "db/changelog/changes/015-confluence-run-metrics.yaml";
  private static final String ORGANIZATION = "00000000-0000-0000-0000-000000000001";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    for (String predecessor : PREDECESSORS) {
      applyChangelog(connection, predecessor);
    }
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheMigrationNeitherMetricsNorTheIncompleteFlagExist() throws Exception {
    assertThat(columnType("indexing_jobs", "requests_sent")).isNull();
    assertThat(columnType("indexing_jobs", "incomplete")).isNull();
  }

  @Test
  void addsNullableMetricsAndANotNullIncompleteFlagDefaultingToFalse() throws Exception {
    UUID existing = insertJob();
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnType("indexing_jobs", "requests_sent")).isEqualTo("integer");
    assertThat(columnType("indexing_jobs", "throttle_count")).isEqualTo("integer");
    assertThat(columnType("indexing_jobs", "throttle_wait_millis")).isEqualTo("bigint");
    assertThat(columnType("indexing_jobs", "attachments_processed")).isEqualTo("integer");
    assertThat(columnType("indexing_jobs", "attachments_skipped")).isEqualTo("integer");
    assertThat(columnType("indexing_jobs", "attachments_failed")).isEqualTo("integer");
    assertThat(columnType("indexing_jobs", "incomplete")).isEqualTo("boolean");
    assertThat(isNullable("indexing_jobs", "requests_sent")).isTrue();
    assertThat(isNullable("indexing_jobs", "incomplete")).isFalse();

    assertThat(requestsSent(existing)).as("existing runs recorded nothing").isNull();
    assertThat(incomplete(existing)).as("existing runs were complete").isFalse();
    UUID fresh = insertJob();
    assertThat(incomplete(fresh)).as("default for a new row").isFalse();

    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE indexing_jobs SET requests_sent = 1234, throttle_count = 2,"
                + " throttle_wait_millis = 90000, incomplete = true WHERE id = ?")) {
      statement.setObject(1, fresh);
      statement.executeUpdate();
    }
    assertThat(requestsSent(fresh)).isEqualTo(1234);
    assertThat(incomplete(fresh)).isTrue();
  }

  private UUID insertJob() throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO indexing_jobs (id, status, last_progress_at, organization_id, triggered_by,"
                + " run_mode) VALUES (?, 'COMPLETED', now(), '"
                + ORGANIZATION
                + "', 'WEBHOOK', 'INCREMENTAL')")) {
      statement.setObject(1, id);
      statement.executeUpdate();
    }
    return id;
  }

  private Integer requestsSent(UUID jobId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT requests_sent FROM indexing_jobs WHERE id = ?")) {
      statement.setObject(1, jobId);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        int value = rs.getInt(1);
        return rs.wasNull() ? null : value;
      }
    }
  }

  private boolean incomplete(UUID jobId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT incomplete FROM indexing_jobs WHERE id = ?")) {
      statement.setObject(1, jobId);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }

  private boolean isNullable(String table, String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'public'"
                + " AND table_name = ? AND column_name = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return "YES".equals(rs.getString(1));
      }
    }
  }

  private String columnType(String table, String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT data_type FROM information_schema.columns WHERE table_schema = 'public'"
                + " AND table_name = ? AND column_name = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }
}
