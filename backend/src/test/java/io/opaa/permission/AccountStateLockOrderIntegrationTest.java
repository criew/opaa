package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Re-evaluating a local account must not add a lock order that the application's own writes run
 * against: one transaction changes the {@code users} row and then the credentials (a change of
 * address and expiry), another changes the credentials first (a lock) and so triggers the
 * re-evaluation. Both have to finish one after the other, never as {@code deadlock detected}.
 */
@OpaaLocalAuthMockMvcTest
class AccountStateLockOrderIntegrationTest {

  private static final Duration WAIT = Duration.ofSeconds(20);

  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;

  private LocalAccountFixtures fixtures;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    fixtures.localProvider(true);
  }

  @AfterEach
  void tearDown() {
    fixtures.cleanUp();
  }

  @Test
  void usersFirstAndCredentialsFirstFinishOneAfterTheOther() throws Exception {
    LocalAccount account =
        fixtures.activeUser("reihenfolge-" + UUID.randomUUID() + "@stadt.example");
    UUID id = account.id();
    try (Connection usersFirst = dataSource.getConnection();
        Connection credentialsFirst = dataSource.getConnection();
        ExecutorService threads = Executors.newFixedThreadPool(2)) {
      usersFirst.setAutoCommit(false);
      credentialsFirst.setAutoCommit(false);
      int usersFirstPid = backendPid(usersFirst);
      int credentialsFirstPid = backendPid(credentialsFirst);

      update(usersFirst, "UPDATE users SET display_name = 'Neu' WHERE id = ?", id);
      Future<?> lock =
          threads.submit(
              () ->
                  update(
                      credentialsFirst,
                      "UPDATE local_credentials SET locked_at = now(), locked_reason = 'ADMIN'"
                          + " WHERE user_id = ?",
                      id));
      // the lock either finishes (it needs only the credentials row) or waits on the users row
      awaitDoneOrWaiting(lock, credentialsFirstPid);
      Future<?> expiry =
          threads.submit(
              () ->
                  update(
                      usersFirst,
                      "UPDATE local_credentials SET expires_at = now() + interval '30 days'"
                          + " WHERE user_id = ?",
                      id));
      awaitDoneOrWaiting(expiry, usersFirstPid);

      lock.get(WAIT.toSeconds(), TimeUnit.SECONDS);
      credentialsFirst.commit();
      expiry.get(WAIT.toSeconds(), TimeUnit.SECONDS);
      usersFirst.commit();
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT cause FROM account_state_history WHERE user_id = ? AND valid_to IS NULL",
                String.class,
                id))
        .isEqualTo("ADMIN_LOCKED");
  }

  private static Void update(Connection connection, String sql, UUID id) {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      statement.executeUpdate();
      return null;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Until the statement has finished or its backend waits on a lock - deterministic, no sleep. */
  private void awaitDoneOrWaiting(Future<?> statement, int pid) {
    Instant deadline = Instant.now().plus(WAIT);
    while (!statement.isDone() && Instant.now().isBefore(deadline)) {
      Boolean waiting =
          jdbc.queryForObject(
              "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = ?"
                  + " AND wait_event_type = 'Lock')",
              Boolean.class,
              pid);
      if (Boolean.TRUE.equals(waiting)) {
        return;
      }
      Thread.onSpinWait();
    }
    assertThat(statement.isDone() || Instant.now().isBefore(deadline)).isTrue();
  }

  private static int backendPid(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()");
        ResultSet result = statement.executeQuery()) {
      result.next();
      return result.getInt(1);
    }
  }
}
