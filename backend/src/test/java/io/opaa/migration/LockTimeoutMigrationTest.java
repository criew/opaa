package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What {@code SET LOCAL lock_timeout} at the start of a changeSet buys during a rolling update: an
 * {@code ALTER TABLE} that waits behind a running read holds its pending exclusive lock request in
 * the queue, and every later query on the table - from every instance - waits behind it. With the
 * timeout the changeSet gives up after it, the queue behind it moves on, and the migration can be
 * repeated; without it the queue stands until the read ends.
 */
class LockTimeoutMigrationTest extends AbstractMigrationTest {

  private static final String FIXTURE = "db/rolling-update/lock-timeout.yaml";

  private ExecutorService executor;
  private Connection reader;

  @Override
  protected List<String> baseFixtureChangelogs() {
    return List.of("db/changelog/test-empty.yaml");
  }

  @BeforeEach
  void startALongRead() throws SQLException {
    executor = Executors.newFixedThreadPool(2);
    try (Connection setup = connect();
        Statement statement = setup.createStatement()) {
      statement.execute("CREATE TABLE lock_fixture (id integer PRIMARY KEY)");
      statement.execute("INSERT INTO lock_fixture VALUES (1)");
    }
    reader = connect();
    reader.setAutoCommit(false);
    try (Statement statement = reader.createStatement()) {
      statement.executeQuery("SELECT count(*) FROM lock_fixture").close();
    }
  }

  @AfterEach
  void endTheRead() throws SQLException {
    executor.shutdownNow();
    reader.rollback();
    reader.close();
  }

  @Test
  void theChangeSetGivesUpAndTheQueriesQueuedBehindItGoOn() throws Exception {
    Future<?> migration = executor.submit(() -> migrate("with-lock-timeout"));
    awaitLockWait();
    Future<Long> queued = executor.submit(this::countRows);

    assertThat(queued.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    assertThatThrownBy(() -> migration.get(10, TimeUnit.SECONDS))
        .hasStackTraceContaining("lock timeout");
    assertThat(hasColumn("added")).isFalse();
  }

  @Test
  void withoutTheTimeoutTheQueriesQueueBehindTheChangeSetUntilTheReadEnds() throws Exception {
    Future<?> migration = executor.submit(() -> migrate("without-lock-timeout"));
    awaitLockWait();
    Future<Long> queued = executor.submit(this::countRows);

    assertThatThrownBy(() -> queued.get(3, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
    reader.rollback();
    migration.get(10, TimeUnit.SECONDS);
    assertThat(queued.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    assertThat(hasColumn("added")).isTrue();
  }

  private Void migrate(String label) throws Exception {
    try (Connection connection = connect()) {
      new Liquibase(FIXTURE, new ClassLoaderResourceAccessor(), liquibaseDatabase(connection))
          .update(new Contexts(), new LabelExpression(label));
      connection.setAutoCommit(true);
    }
    return null;
  }

  private long countRows() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT count(*) FROM lock_fixture")) {
      rows.next();
      return rows.getLong(1);
    }
  }

  /** Until the migration's ALTER TABLE waits for its lock on the table. */
  private void awaitLockWait() throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    try (Connection observer = connect();
        Statement statement = observer.createStatement()) {
      while (System.nanoTime() < deadline) {
        try (ResultSet rows =
            statement.executeQuery(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                    + " AND wait_event_type = 'Lock' AND query ILIKE 'ALTER TABLE lock_fixture%'")) {
          rows.next();
          if (rows.getLong(1) > 0) {
            return;
          }
        }
        Thread.sleep(50);
      }
    }
    throw new AssertionError("the migration never waited for its lock");
  }

  private boolean hasColumn(String column) throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT count(*) FROM pg_attribute WHERE attrelid = 'lock_fixture'::regclass"
                    + " AND attname = '"
                    + column
                    + "' AND NOT attisdropped")) {
      rows.next();
      return rows.getLong(1) > 0;
    }
  }
}
