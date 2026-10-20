package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * {@code rights/2026-10-19-permission-history-boundary.yaml}: the boundaries of the permission
 * history come from the database clock and are strictly increasing across every connection, so
 * across every instance of the installation (ADR-0032, Entscheidung 3; ADR-0053, Entscheidung 9).
 * Each test gets a database of its own, so a sequence pushed into the future here touches no other
 * test.
 */
class PermissionHistoryBoundaryMigrationTest extends AbstractBaselineTest {

  private static final String FILE =
      "db/changelog/rights/2026-10-19-permission-history-boundary.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void anExistingHistoryIsContinuedAboveItsLatestBoundary() throws Exception {
    // A boundary the host clock of an earlier process issued ahead of the database clock: the
    // first boundary of the database must still lie above it, or the next interval of that object
    // would end before it starts.
    Instant ahead = Instant.now().plus(Duration.ofMinutes(5)).truncatedTo(ChronoUnit.MICROS);
    UUID user = insertUser();
    execute(
        "INSERT INTO account_state_history (id, user_id, organization_id, state, cause,"
            + " valid_from) VALUES ('"
            + UUID.randomUUID()
            + "', '"
            + user
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', 'ACTIVE', 'ACCOUNT_CREATED', '"
            + ahead
            + "')");

    applyChangelog(connection, FILE);

    assertThat(nextBoundary(connection)).isEqualTo(ahead.plus(1, ChronoUnit.MICROS));
  }

  @Test
  void aFreshInstallationStartsAtTheDatabaseClock() throws Exception {
    applyChangelog(connection, FILE);
    Instant before = databaseNow();

    Instant boundary = nextBoundary(connection);

    assertThat(boundary).isAfterOrEqualTo(before).isBeforeOrEqualTo(databaseNow());
  }

  @Test
  void aDatabaseClockBehindTheLastBoundaryStillYieldsStrictlyIncreasingBoundaries()
      throws Exception {
    // regression guard for #1497: a standing clock - here the database clock behind the last
    // boundary, as after it stepped back - yields one microsecond more per call, never the same.
    applyChangelog(connection, FILE);
    Instant last = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
    setLastBoundary(last);

    List<Instant> boundaries = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      boundaries.add(nextBoundary(connection));
    }

    assertThat(boundaries)
        .containsExactly(
            last.plus(1, ChronoUnit.MICROS),
            last.plus(2, ChronoUnit.MICROS),
            last.plus(3, ChronoUnit.MICROS),
            last.plus(4, ChronoUnit.MICROS));
  }

  @Test
  void theDatabaseClockIsUsedAgainOnceItHasPassedTheLastBoundary() throws Exception {
    applyChangelog(connection, FILE);
    setLastBoundary(Instant.now().minus(Duration.ofHours(1)));
    Instant before = databaseNow();

    assertThat(nextBoundary(connection)).isAfterOrEqualTo(before);
  }

  @Test
  void connectionsOfDifferentInstancesNeverShareABoundaryAndStayOrdered() throws Exception {
    // Each connection stands for an instance; their host clocks play no part at all.
    applyChangelog(connection, FILE);
    int instances = 6;
    int perInstance = 200;

    List<Instant> all = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(instances)) {
      List<Callable<List<Instant>>> tasks = new ArrayList<>();
      for (int i = 0; i < instances; i++) {
        tasks.add(
            () -> {
              try (Connection own = connect()) {
                List<Instant> issued = new ArrayList<>();
                for (int n = 0; n < perInstance; n++) {
                  issued.add(nextBoundary(own));
                }
                return issued;
              }
            });
      }
      for (Future<List<Instant>> future : pool.invokeAll(tasks)) {
        List<Instant> issued = future.get();
        assertThat(issued).as("each instance sees its own boundaries rise").isSorted();
        all.addAll(issued);
      }
    }
    Set<Instant> distinct = new HashSet<>(all);
    assertThat(distinct).hasSize(instances * perInstance);
  }

  @Test
  void aBoundaryKeepsTheMicrosecondResolutionTheHistoryStores() throws Exception {
    applyChangelog(connection, FILE);

    Instant boundary = nextBoundary(connection);

    assertThat(boundary.getNano() % 1_000).isZero();
  }

  private Instant nextBoundary(Connection on) throws SQLException {
    try (Statement statement = on.createStatement();
        ResultSet result = statement.executeQuery("SELECT permission_history_next_boundary()")) {
      result.next();
      return result.getObject(1, OffsetDateTime.class).toInstant();
    }
  }

  private Instant databaseNow() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT clock_timestamp()")) {
      result.next();
      return result.getObject(1, OffsetDateTime.class).toInstant();
    }
  }

  private void setLastBoundary(Instant last) throws SQLException {
    long micros = ChronoUnit.MICROS.between(Instant.EPOCH, last);
    execute("SELECT setval('permission_history_boundary_seq', " + micros + ")");
  }
}
