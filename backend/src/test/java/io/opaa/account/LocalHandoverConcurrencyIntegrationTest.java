package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.auth.LocalIssuer;
import io.opaa.auth.OidcProvider;
import io.opaa.common.ValidationException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The redemption of a handover decides on the credentials as they are once it holds their row: a
 * lock, an inactivity lock or an expiry committed while it runs refuses it instead of being deleted
 * with the row. It also takes the credentials before it changes the key of the {@code users} row,
 * so it finishes one after the other with a lock of the account or the switch of the local
 * accounts, whose state history holds the credentials and then reads that {@code users} row.
 */
@OpaaLocalAuthMockMvcTest
class LocalHandoverConcurrencyIntegrationTest {

  private static final Duration WAIT = Duration.ofSeconds(20);
  private static final String ISSUER = "https://idp.test.example/realms/uebergabe-nebenlaeufig";

  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private LocalHandoverAccountService handovers;
  @Autowired private LocalActionTokenService actionTokens;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;

  private LocalAccountFixtures fixtures;
  private OidcProvider local;
  private OidcProvider provider;
  private final List<UUID> accounts = new ArrayList<>();

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    local = fixtures.localProvider(true);
    provider = fixtures.oidcProvider("Beschäftigte", ISSUER, "opaa-frontend");
  }

  @AfterEach
  void tearDown() {
    accounts.forEach(fixtures::deleteAccount);
    accounts.clear();
    fixtures.localProvider(true);
    fixtures.cleanUp();
    fixtures.deleteProvider(provider.getId());
  }

  /**
   * The administration's lock, the inactivity lock and an expiry are written while the redemption
   * waits for the credentials: the redemption reads them and refuses, the account stays local.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "locked_at = now(), locked_reason = 'ADMIN'",
        "locked_at = now(), locked_reason = 'INACTIVITY'",
        "expires_at = now() - interval '1 minute'"
      })
  void aLockOrExpiryCommittedDuringTheRedemptionRefusesIt(String change) throws Exception {
    LocalAccount person = person();
    String code = handoverCode(person.id());

    Future<?> redemption;
    try (Connection administration = dataSource.getConnection();
        ExecutorService threads = Executors.newSingleThreadExecutor()) {
      administration.setAutoCommit(false);
      int administrationPid = backendPid(administration);
      update(
          administration,
          "UPDATE local_credentials SET " + change + " WHERE user_id = ?",
          person.id());
      redemption = threads.submit(() -> redeem(code));
      awaitDoneOrWaiting(redemption, administrationPid);
      administration.commit();
      assertThatThrownBy(() -> redemption.get(WAIT.toSeconds(), TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(ValidationException.class);
    }

    assertThat(issuerOf(person.id())).isEqualTo(LocalIssuer.URN);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM local_credentials WHERE user_id = ?",
                Integer.class,
                person.id()))
        .isOne();
  }

  /**
   * The inactivity lock holds the credentials, then the redemption starts, then the lock writes:
   * the redemption waits for the credentials and refuses afterwards, the lock is never caught in
   * {@code deadlock detected} against the redemption's write to {@code users}.
   */
  @Test
  void aLockHoldingTheCredentialsFirstFinishesAndTheRedemptionIsRefused() throws Exception {
    LocalAccount person = person();
    Throwable outcome =
        redeemWhileAnotherTransactionHoldsTheCredentials(
            "SELECT 1 FROM local_credentials WHERE user_id = ? FOR NO KEY UPDATE",
            "UPDATE local_credentials SET locked_at = now(), locked_reason = 'INACTIVITY'"
                + " WHERE user_id = ?",
            person.id(),
            person.id());

    assertThat(outcome).isInstanceOf(ValidationException.class);
    assertThat(issuerOf(person.id())).isEqualTo(LocalIssuer.URN);
  }

  /**
   * The switch of the local accounts locks every local account's credentials and then switches off,
   * which re-evaluates them all: the redemption waits and then completes, both without {@code
   * deadlock detected}.
   */
  @Test
  void switchingOffTheLocalAccountsFinishesAndTheRedemptionCompletesAfterIt() throws Exception {
    LocalAccount person = person();
    Throwable outcome =
        redeemWhileAnotherTransactionHoldsTheCredentials(
            "SELECT c.user_id FROM local_credentials c JOIN users u ON u.id = c.user_id"
                + " WHERE u.issuer = 'urn:opaa:local' AND ? IS NOT NULL"
                + " ORDER BY c.user_id FOR NO KEY UPDATE OF c",
            "UPDATE oidc_providers SET enabled = false WHERE id = ?",
            person.id(),
            local.getId());

    assertThat(outcome).isNull();
    assertThat(issuerOf(person.id())).isEqualTo(ISSUER);
  }

  /**
   * Runs {@code hold} on a transaction of its own, starts the redemption, waits until it waits,
   * runs {@code write} and commits: the write must not fail, the redemption's outcome - null when
   * it completed - is returned.
   */
  private Throwable redeemWhileAnotherTransactionHoldsTheCredentials(
      String hold, String write, UUID holdParameter, UUID writeParameter) throws Exception {
    String code = handoverCode(holdParameter);
    try (Connection other = dataSource.getConnection();
        ExecutorService threads = Executors.newSingleThreadExecutor()) {
      other.setAutoCommit(false);
      int otherPid = backendPid(other);
      query(other, hold, holdParameter);
      Future<?> redemption = threads.submit(() -> redeem(code));
      awaitDoneOrWaiting(redemption, otherPid);
      update(other, write, writeParameter);
      other.commit();
      try {
        redemption.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        return null;
      } catch (ExecutionException failed) {
        return failed.getCause();
      }
    }
  }

  private LocalAccount person() {
    LocalAccount person = fixtures.activeUser("uebergabe-" + UUID.randomUUID() + "@stadt.example");
    accounts.add(person.id());
    return person;
  }

  private String handoverCode(UUID account) {
    return actionTokens
        .issueHandover(
            account,
            LocalHandoverAccountService.HANDOVER_TTL,
            provider.getId(),
            "Umstellung auf den Identitätsanbieter")
        .rawToken();
  }

  private Void redeem(String code) {
    handovers.redeem(code, provider, "subject-" + UUID.randomUUID());
    return null;
  }

  private String issuerOf(UUID account) {
    return jdbc.queryForObject("SELECT issuer FROM users WHERE id = ?", String.class, account);
  }

  private static void query(Connection connection, String sql, UUID id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      statement.executeQuery().close();
    }
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

  /**
   * Until the task has finished or a backend other than {@code excluded} waits on a lock - the
   * redemption runs on a pool connection of its own, whose pid the test does not know.
   */
  private void awaitDoneOrWaiting(Future<?> task, int excluded) {
    awaitDoneOr(
        task,
        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
            + " AND datname = current_database() AND pid <> pg_backend_pid() AND pid <> "
            + excluded
            + ")");
  }

  /** Polls {@code waitingQuery} until it holds or the task is done; deterministic, no sleep. */
  private void awaitDoneOr(Future<?> task, String waitingQuery) {
    Instant deadline = Instant.now().plus(WAIT);
    while (!task.isDone() && Instant.now().isBefore(deadline)) {
      if (Boolean.TRUE.equals(jdbc.queryForObject(waitingQuery, Boolean.class))) {
        return;
      }
      Thread.onSpinWait();
    }
    assertThat(task.isDone()).as("the task neither finished nor waited").isTrue();
  }

  private static int backendPid(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()");
        ResultSet result = statement.executeQuery()) {
      result.next();
      return result.getInt(1);
    }
  }
}
