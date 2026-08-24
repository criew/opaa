package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies Liquibase changelog 065 in isolation against a database built from {@code
 * test-master-through-046.yaml} - already past changelog 037 ({@code indexing_run_events} creation)
 * - plus changelogs 056/057 (both category widenings), the same base {@code
 * Migration057WidenIndexingRunEventCategoryFormatMismatchTest} uses for its own predecessor.
 *
 * <p>Proves #862's acceptance criteria against a real database: {@code
 * chk_indexing_run_events_category} no longer exists after 065 runs, a value outside the old closed
 * list is now writable, and every value the constraint accepted before 065 is still accepted
 * afterwards. Unlike {@code audit_log}, {@code indexing_run_events} is not ownership-restricted -
 * there is no separate grant to re-prove here.
 */
class Migration065DropIndexingRunEventsCategoryCheckTest extends AbstractMigrationTest {

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-046.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    applyChangelog(
        connection,
        "db/changelog/changes/056-widen-indexing-run-event-category-schedule-skipped.yaml");
    applyChangelog(
        connection,
        "db/changelog/changes/057-widen-indexing-run-event-category-format-mismatch.yaml");
    applyChangelog(
        connection, "db/changelog/changes/065-drop-indexing-run-events-category-check.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void theCheckConstraintNoLongerExists() throws Exception {
    assertThat(constraintExists("chk_indexing_run_events_category")).isFalse();
  }

  @Test
  void aValueOutsideTheFormerClosedListIsNowWritable() throws Exception {
    UUID jobId = insertJob();

    insertEvent(jobId, "NOT_A_REAL_CATEGORY");

    assertThat(categoryOf(jobId)).isEqualTo("NOT_A_REAL_CATEGORY");
  }

  @Test
  void everyPreExistingCategoryIsStillAccepted() throws Exception {
    UUID jobId = insertJob();

    for (String category :
        new String[] {
          "REJECTED",
          "UNREACHABLE",
          "UNSUPPORTED_FORMAT",
          "ALLOWLIST",
          "ERROR",
          "SCHEDULE_SKIPPED",
          "FORMAT_MISMATCH"
        }) {
      insertEvent(jobId, category);
    }
  }

  private boolean constraintExists(String constraintName) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM pg_constraint WHERE conname = '" + constraintName + "'")) {
      result.next();
      return result.getInt(1) > 0;
    }
  }

  private UUID insertJob() throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO indexing_jobs (id, status, started_at, last_progress_at) VALUES ('"
              + id
              + "', 'RUNNING', now(), now())");
    }
    return id;
  }

  private void insertEvent(UUID jobId, String category) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO indexing_run_events (id, job_id, category, message) VALUES ('"
              + id
              + "', '"
              + jobId
              + "', '"
              + category
              + "', 'Testereignis')");
    }
  }

  private String categoryOf(UUID jobId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT category FROM indexing_run_events WHERE job_id = '" + jobId + "'")) {
      result.next();
      return result.getString(1);
    }
  }
}
