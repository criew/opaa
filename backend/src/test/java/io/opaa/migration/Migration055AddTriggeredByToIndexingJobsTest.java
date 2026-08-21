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

/**
 * Applies Liquibase changelog 055 in isolation against a database built from {@code
 * test-master-through-046.yaml} - the same "siblings stop at 046, independently" fixture {@code
 * Migration054AddScheduleToKnowledgeLibrariesTest} and {@code
 * Migration056WidenIndexingRunEventCategoryScheduleSkippedTest} use.
 *
 * <p>Covers what {@code IndexingJobServiceTest}'s mocked repository cannot: that {@code
 * triggered_by} actually defaults every pre-existing row shape to {@code MANUAL} and that {@code
 * chk_indexing_jobs_triggered_by} rejects an unknown value at the database level.
 */
class Migration055AddTriggeredByToIndexingJobsTest extends AbstractMigrationTest {

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-046.yaml";
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
  void aJobInsertedWithoutTriggeredByDefaultsToManual() throws Exception {
    applyChangelog055();

    UUID jobId = insertJobWithoutTriggeredBy();

    assertThat(triggeredByOf(jobId)).isEqualTo("MANUAL");
  }

  @Test
  void rejectsAnUnknownTriggeredByValue() throws Exception {
    applyChangelog055();

    assertThatThrownBy(() -> insertJobWithTriggeredBy("AUTOMATIC"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_indexing_jobs_triggered_by");
  }

  @Test
  void acceptsScheduledAsATriggeredByValue() throws Exception {
    applyChangelog055();

    UUID jobId = insertJobWithTriggeredBy("SCHEDULED");

    assertThat(triggeredByOf(jobId)).isEqualTo("SCHEDULED");
  }

  private void applyChangelog055() throws Exception {
    applyChangelog(connection, "db/changelog/changes/055-add-triggered-by-to-indexing-jobs.yaml");
  }

  private UUID insertJobWithoutTriggeredBy() throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO indexing_jobs (id, status, started_at, last_progress_at) VALUES ('"
              + id
              + "', 'RUNNING', now(), now())");
    }
    return id;
  }

  private UUID insertJobWithTriggeredBy(String triggeredBy) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO indexing_jobs (id, status, started_at, last_progress_at, triggered_by)"
              + " VALUES ('"
              + id
              + "', 'RUNNING', now(), now(), '"
              + triggeredBy
              + "')");
    }
    return id;
  }

  private String triggeredByOf(UUID jobId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT triggered_by FROM indexing_jobs WHERE id = '" + jobId + "'")) {
      result.next();
      return result.getString(1);
    }
  }
}
