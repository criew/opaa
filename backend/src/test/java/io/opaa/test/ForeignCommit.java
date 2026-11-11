package io.opaa.test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

/**
 * Commits one statement from a session of its own, on a thread of its own, while the caller's
 * transaction stays open: the caller has read a row and meets the change only at its write, the
 * window a concurrent act has in production. A statement that waits behind a lock the caller holds
 * fails after five seconds instead of hanging the test, unless it was {@link #start started} and is
 * awaited after the caller's commit.
 */
public final class ForeignCommit {

  private final Thread session;
  private final AtomicReference<Throwable> failure;
  private final String sql;

  private ForeignCommit(Thread session, AtomicReference<Throwable> failure, String sql) {
    this.session = session;
    this.failure = failure;
    this.sql = sql;
  }

  /** Runs and commits {@code sql}, returning once it is committed. */
  public static void commit(DataSource dataSource, String sql, Object... args) {
    start(dataSource, "5s", sql, args).awaitCommitted();
  }

  /**
   * Starts {@code sql} without waiting for it; it may wait up to 30 seconds for a lock the caller
   * holds. {@link #isWaiting} tells whether it is still blocked, {@link #awaitCommitted} joins it.
   */
  public static ForeignCommit start(DataSource dataSource, String sql, Object... args) {
    return start(dataSource, "30s", sql, args);
  }

  private static ForeignCommit start(
      DataSource dataSource, String lockTimeout, String sql, Object... args) {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread session =
        Thread.ofPlatform()
            .start(
                () -> {
                  try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try (Statement timeout = connection.createStatement()) {
                      timeout.execute("SET LOCAL lock_timeout = '" + lockTimeout + "'");
                    }
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                      for (int i = 0; i < args.length; i++) {
                        statement.setObject(i + 1, args[i]);
                      }
                      statement.executeUpdate();
                    }
                    connection.commit();
                  } catch (Throwable e) {
                    failure.set(e);
                  }
                });
    return new ForeignCommit(session, failure, sql);
  }

  /** Whether the statement is still running after {@code grace}, e.g. blocked behind a lock. */
  public boolean isWaiting(Duration grace) {
    try {
      return !session.join(grace);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while watching the foreign commit", e);
    }
  }

  public void awaitCommitted() {
    try {
      if (!session.join(Duration.ofSeconds(40))) {
        throw new AssertionError("the foreign commit did not finish: " + sql);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for the foreign commit", e);
    }
    if (failure.get() != null) {
      throw new AssertionError("the foreign commit failed: " + sql, failure.get());
    }
  }
}
