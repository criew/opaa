package io.opaa.test;

import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two instances doing the same piece of work at the same moment, made deterministic: a session of
 * its own locks the row both will write, both runs start on threads of their own, and the lock is
 * let go only once both wait behind it - whether at a locking read or at the write itself. A run
 * that reads without a lock therefore reaches its write with what it read before the other one
 * committed, which is exactly the window two instances have in production.
 */
public final class TwoInstanceRace {

  private final DataSource dataSource;
  private final JdbcTemplate jdbc;

  public TwoInstanceRace(DataSource dataSource) {
    this.dataSource = dataSource;
    this.jdbc = new JdbcTemplate(dataSource);
  }

  /**
   * Runs {@code first} and {@code second} concurrently behind a lock on the row that {@code
   * lockRowSql} - a {@code SELECT … FOR UPDATE} - selects with {@code args}, and returns once both
   * have finished; an exception of either is rethrown.
   */
  public void run(Runnable first, Runnable second, String lockRowSql, Object... args)
      throws Exception {
    List<Throwable> failures = new CopyOnWriteArrayList<>();
    List<Thread> threads = new ArrayList<>();
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try {
        lockRow(holder, lockRowSql, args);
        int holderPid = pidOf(holder);
        for (Runnable run : List.of(first, second)) {
          threads.add(
              Thread.ofPlatform()
                  .start(
                      () -> {
                        try {
                          run.run();
                        } catch (Throwable e) {
                          failures.add(e);
                        }
                      }));
        }
        awaitWaitingBehind(holderPid, 2, threads);
      } finally {
        holder.rollback();
      }
    }
    for (Thread thread : threads) {
      thread.join(Duration.ofSeconds(30));
    }
    if (!failures.isEmpty()) {
      throw new AssertionError("a racing run failed", failures.getFirst());
    }
  }

  private static void lockRow(Connection holder, String sql, Object... args) throws SQLException {
    try (PreparedStatement statement = holder.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        statement.setObject(i + 1, args[i]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new IllegalStateException("the row to race on does not exist: " + sql);
        }
      }
    }
  }

  private static int pidOf(Connection connection) throws SQLException {
    try (PreparedStatement pid = connection.prepareStatement("SELECT pg_backend_pid()");
        ResultSet rows = pid.executeQuery()) {
      rows.next();
      return rows.getInt(1);
    }
  }

  /**
   * Waits until {@code count} sessions wait for a lock the holder holds, directly or queued behind
   * another waiter for it - or until a run has finished without waiting, which a test then sees in
   * its result.
   */
  private void awaitWaitingBehind(int holderPid, int count, List<Thread> threads) {
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () -> {
              long finished = threads.stream().filter(thread -> !thread.isAlive()).count();
              Integer waiting =
                  jdbc.queryForObject(
                      "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                          + " AND (? = ANY(pg_blocking_pids(pid)) OR EXISTS (SELECT 1 FROM"
                          + " pg_stat_activity w WHERE w.pid = ANY(pg_blocking_pids("
                          + "pg_stat_activity.pid)) AND ? = ANY(pg_blocking_pids(w.pid))))",
                      Integer.class,
                      holderPid,
                      holderPid);
              return waiting + finished >= count;
            });
  }
}
