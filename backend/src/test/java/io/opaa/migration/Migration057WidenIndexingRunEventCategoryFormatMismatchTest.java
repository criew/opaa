package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies Liquibase changelog 057 in isolation against a database built from {@code
 * test-master-through-046.yaml} plus changelog 056 (applied per test, same as {@code
 * Migration056WidenIndexingRunEventCategoryScheduleSkippedTest} itself does for its own
 * predecessor) - already past migration 037 ({@code indexing_run_events} creation).
 *
 * <p>Proves the bug #229 surfaced while validating the Rheinfurt demo corpus against a real Compose
 * stack: {@code chk_indexing_run_events_category} never accepted {@code FORMAT_MISMATCH} (#404).
 * {@code IndexingRunEventRecorder#record} already catches any persistence failure here (PR #604
 * review, finding 2, "Never breaks the run"), so this was never a document-indexing bug - no run
 * failed and no document was skipped because of it. What actually happened is narrower: every
 * {@code FORMAT_MISMATCH} event was silently dropped instead of persisted, and {@code
 * IndexingJob#eventsTruncatedCount} was incremented for each one instead, overstating a run's own
 * "… und N weitere" without any of those events ever being either shown or genuinely truncated for
 * capacity reasons.
 */
class Migration057WidenIndexingRunEventCategoryFormatMismatchTest extends AbstractMigrationTest {

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
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheMigrationFormatMismatchIsRejected() throws Exception {
    UUID jobId = insertJob();

    assertThatThrownBy(() -> insertEvent(jobId, "FORMAT_MISMATCH"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_indexing_run_events_category");
  }

  @Test
  void afterTheMigrationFormatMismatchIsAccepted() throws Exception {
    UUID jobId = insertJob();
    applyChangelog057();

    insertEvent(jobId, "FORMAT_MISMATCH");

    assertThat(categoryOf(jobId)).isEqualTo("FORMAT_MISMATCH");
  }

  @Test
  void afterTheMigrationEveryPreExistingCategoryIsStillAccepted() throws Exception {
    UUID jobId = insertJob();
    applyChangelog057();

    for (String category :
        new String[] {
          "REJECTED", "UNREACHABLE", "UNSUPPORTED_FORMAT", "ALLOWLIST", "ERROR", "SCHEDULE_SKIPPED"
        }) {
      insertEvent(jobId, category);
    }
  }

  @Test
  void rollbackRestoresTheConstraintAndRejectsFormatMismatchAgain() throws Exception {
    // No FORMAT_MISMATCH row exists at rollback time here - Postgres validates ADD CONSTRAINT
    // against every existing row, so the rollback itself would fail (a real, separate operational
    // concern for an operator rolling back after FORMAT_MISMATCH rows have already accumulated, not
    // something this changeSet's rollback block can paper over - identical reasoning to 056's own
    // rollback test). What this test proves is narrower: the restored constraint rejects a new
    // FORMAT_MISMATCH insert afterwards, exactly as it did before this changeSet ever ran.
    UUID jobId = insertJob();
    applyChangelog057();

    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/057-widen-indexing-run-event-category-format-mismatch.yaml",
            new ClassLoaderResourceAccessor(),
            liquibaseDatabase(connection));
    liquibase.rollback(1, (String) null);
    connection.setAutoCommit(true);

    assertThatThrownBy(() -> insertEvent(jobId, "FORMAT_MISMATCH"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_indexing_run_events_category");
  }

  private void applyChangelog057() throws Exception {
    applyChangelog(
        connection,
        "db/changelog/changes/057-widen-indexing-run-event-category-format-mismatch.yaml");
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
