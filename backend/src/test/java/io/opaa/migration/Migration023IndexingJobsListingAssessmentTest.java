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
 * Delta test for {@code changes/023-indexing-jobs-listing-assessment.yaml} (#1191): the two
 * nullable columns carrying a run's listing assessment - absent before the changeSet; after it,
 * every existing run reads {@code NULL} (never assessed), and an assessing run can store its
 * verdict and the affected space keys.
 */
class Migration023IndexingJobsListingAssessmentTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/023-indexing-jobs-listing-assessment.yaml";
  private static final UUID ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws SQLException {
    connection = connect();
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void addsTwoNullableColumnsThatExistingRunsReadAsNull() throws Exception {
    assertThat(columnType("listing_complete")).as("column absent before the changeSet").isNull();
    assertThat(columnType("unreadable_space_keys"))
        .as("column absent before the changeSet")
        .isNull();
    UUID jobId = insertJob();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnType("listing_complete")).isEqualTo("boolean");
    assertThat(columnType("unreadable_space_keys")).isEqualTo("text");
    assertThat(listingCompleteOf(jobId)).as("an existing run never assessed its listing").isNull();
    setAssessment(jobId, false, "SEC,IT");
    assertThat(listingCompleteOf(jobId)).isFalse();
    assertThat(unreadableSpaceKeysOf(jobId)).isEqualTo("SEC,IT");
  }

  private String columnType(String columnName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT data_type FROM information_schema.columns WHERE table_name = 'indexing_jobs'"
                + " AND column_name = ?")) {
      statement.setString(1, columnName);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  private UUID insertJob() throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO indexing_jobs (id, status, last_progress_at, organization_id) VALUES"
                + " (?, 'COMPLETED', now(), ?)")) {
      statement.setObject(1, id);
      statement.setObject(2, ORGANIZATION_ID);
      statement.executeUpdate();
    }
    return id;
  }

  private Boolean listingCompleteOf(UUID jobId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT listing_complete FROM indexing_jobs WHERE id = ?")) {
      statement.setObject(1, jobId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        boolean value = rs.getBoolean(1);
        return rs.wasNull() ? null : value;
      }
    }
  }

  private String unreadableSpaceKeysOf(UUID jobId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT unreadable_space_keys FROM indexing_jobs WHERE id = ?")) {
      statement.setObject(1, jobId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getString(1);
      }
    }
  }

  private void setAssessment(UUID jobId, boolean complete, String keys) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE indexing_jobs SET listing_complete = ?, unreadable_space_keys = ? WHERE id ="
                + " ?")) {
      statement.setBoolean(1, complete);
      statement.setString(2, keys);
      statement.setObject(3, jobId);
      statement.executeUpdate();
    }
  }
}
