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
 * fails after five seconds instead of hanging the test.
 */
public final class ForeignCommit {

  private ForeignCommit() {}

  public static void commit(DataSource dataSource, String sql, Object... args) {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread session =
        Thread.ofPlatform()
            .start(
                () -> {
                  try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try (Statement timeout = connection.createStatement()) {
                      timeout.execute("SET LOCAL lock_timeout = '5s'");
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
    try {
      if (!session.join(Duration.ofSeconds(30))) {
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
