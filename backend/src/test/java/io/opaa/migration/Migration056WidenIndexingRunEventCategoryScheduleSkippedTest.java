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
 * Applies Liquibase changelog 056 in isolation against a database built from {@code
 * test-master-through-046.yaml} - already past migration 037 ({@code indexing_run_events}
 * creation), the same "siblings stop at 046, independently" fixture {@code
 * Migration049BindIndexingJobsToOrganizationTest} and {@code
 * Migration054AddScheduleToKnowledgeLibrariesTest} already use.
 *
 * <p>Covers what {@code LibraryIndexingSchedulerTest}'s mocked repositories cannot: that {@code
 * chk_indexing_run_events_category} actually rejects {@code SCHEDULE_SKIPPED} before this changeSet
 * runs, accepts it afterwards, and that the rollback restores the pre-#485 constraint - the same
 * widen-then-rollback shape {@code Migration035LibrarySourceUpdatedEventTypeTest} already proves
 * for {@code audit_log}'s equivalent constraint.
 */
class Migration056WidenIndexingRunEventCategoryScheduleSkippedTest extends AbstractMigrationTest {

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
  void beforeTheMigrationScheduleSkippedIsRejected() throws Exception {
    UUID jobId = insertJob();

    assertThatThrownBy(() -> insertEvent(jobId, "SCHEDULE_SKIPPED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_indexing_run_events_category");
  }

  @Test
  void afterTheMigrationScheduleSkippedIsAccepted() throws Exception {
    UUID jobId = insertJob();
    applyChangelog056();

    insertEvent(jobId, "SCHEDULE_SKIPPED");

    assertThat(categoryOf(jobId)).isEqualTo("SCHEDULE_SKIPPED");
  }

  @Test
  void afterTheMigrationEveryPreExistingCategoryIsStillAccepted() throws Exception {
    UUID jobId = insertJob();
    applyChangelog056();

    for (String category :
        new String[] {"REJECTED", "UNREACHABLE", "UNSUPPORTED_FORMAT", "ALLOWLIST", "ERROR"}) {
      insertEvent(jobId, category);
    }
  }

  @Test
  void rollbackRestoresTheConstraintAndRejectsScheduleSkippedAgain() throws Exception {
    // No SCHEDULE_SKIPPED row exists at rollback time here - Postgres validates ADD CONSTRAINT
    // against every existing row, so the rollback itself would fail (a real, separate operational
    // concern for an operator rolling back after SCHEDULE_SKIPPED rows have already accumulated,
    // not something this changeSet's rollback block can paper over). What this test proves is
    // narrower and still the point of #705 review, item 5: the restored constraint rejects a new
    // SCHEDULE_SKIPPED insert afterwards, exactly as it did before this changeSet ever ran.
    UUID jobId = insertJob();
    applyChangelog056();

    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/056-widen-indexing-run-event-category-schedule-skipped.yaml",
            new ClassLoaderResourceAccessor(),
            liquibaseDatabase(connection));
    liquibase.rollback(1, (String) null);
    connection.setAutoCommit(true);

    assertThatThrownBy(() -> insertEvent(jobId, "SCHEDULE_SKIPPED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_indexing_run_events_category");
  }

  private void applyChangelog056() throws Exception {
    applyChangelog(
        connection,
        "db/changelog/changes/056-widen-indexing-run-event-category-schedule-skipped.yaml");
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
