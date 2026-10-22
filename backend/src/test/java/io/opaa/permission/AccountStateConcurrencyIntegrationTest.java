package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.auth.AccountUsability;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
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
 * The history stays right when two transactions change what one account's usability depends on at
 * the same time: every re-evaluation of an account waits for the same row lock until the other
 * commits and then reads afresh, so the last one to commit sees both changes. And every column the
 * rule reads re-evaluates when it changes, the order of two provider rows of one issuer included.
 */
@OpaaLocalAuthMockMvcTest
class AccountStateConcurrencyIntegrationTest {

  private static final Duration WAIT = Duration.ofSeconds(20);
  private static final String PREFIX = "https://idp.example/concurrency-it/";

  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository users;
  @Autowired private AccountUsability usability;

  private LocalAccountFixtures fixtures;
  private final List<UUID> accounts = new ArrayList<>();
  private final List<UUID> providers = new ArrayList<>();

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    fixtures.localProvider(true);
  }

  @AfterEach
  void tearDown() {
    accounts.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
    providers.forEach(id -> jdbc.update("DELETE FROM oidc_providers WHERE id = ?", id));
    fixtures.cleanUp();
    accounts.clear();
    providers.clear();
  }

  /**
   * The directory lifts an account's lock and, before that commits, the provider is switched off:
   * the switch waits for the account and then reads the lifted lock - the account ends locked.
   */
  @Test
  void aProviderSwitchedOffWhileTheDirectoryLiftsALockLeavesTheAccountLocked() throws Exception {
    String issuer = PREFIX + UUID.randomUUID();
    UUID provider = provider("Anbieter", issuer, true, 0);
    UUID account = oidcAccount(issuer);
    jdbc.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", account);

    try (Connection directory = dataSource.getConnection();
        Connection administration = dataSource.getConnection();
        ExecutorService threads = Executors.newSingleThreadExecutor()) {
      directory.setAutoCommit(false);
      administration.setAutoCommit(false);
      int administrationPid = backendPid(administration);
      update(directory, "UPDATE users SET directory_locked_at = NULL WHERE id = ?", account);
      Future<?> switchOff =
          threads.submit(
              () ->
                  update(
                      administration,
                      "UPDATE oidc_providers SET enabled = false WHERE id = ?",
                      provider));
      awaitWaitingOnALock(switchOff, administrationPid);
      directory.commit();
      switchOff.get(WAIT.toSeconds(), TimeUnit.SECONDS);
      administration.commit();
    }

    assertThat(openCause(account)).isEqualTo("PROVIDER_DISABLED");
    assertAgreesWithTheRule(account);
  }

  /**
   * The expiry sweep meets an administrator who has just extended the account: it waits, reads the
   * new date and records nothing.
   */
  @Test
  void theExpirySweepWaitsForAnExtensionAndRecordsNothing() throws Exception {
    LocalAccount user = fixtures.activeUser("verlaengert-" + UUID.randomUUID() + "@stadt.example");
    // an expiry that passes without any write: not yet recorded, only the sweep would see it
    jdbc.update(
        "UPDATE local_credentials SET expires_at = clock_timestamp() + interval '1 second'"
            + " WHERE user_id = ?",
        user.id());
    jdbc.queryForObject(
        "SELECT pg_sleep(greatest(0, extract(epoch FROM (expires_at - clock_timestamp())) + 0.05))"
            + " FROM local_credentials WHERE user_id = ?",
        Object.class,
        user.id());
    assertThat(openCause(user.id())).isNull();

    try (Connection administration = dataSource.getConnection();
        Connection sweep = dataSource.getConnection();
        ExecutorService threads = Executors.newSingleThreadExecutor()) {
      administration.setAutoCommit(false);
      sweep.setAutoCommit(false);
      int sweepPid = backendPid(sweep);
      update(
          administration,
          "UPDATE local_credentials SET expires_at = now() + interval '30 days' WHERE user_id = ?",
          user.id());
      Future<?> run =
          threads.submit(
              () -> {
                try (PreparedStatement statement =
                    sweep.prepareStatement(
                        "SELECT account_state_record_reached_expiries(1000000)")) {
                  statement.executeQuery().close();
                } catch (SQLException e) {
                  throw new IllegalStateException(e);
                }
                return null;
              });
      awaitWaitingOnALock(run, sweepPid);
      administration.commit();
      run.get(WAIT.toSeconds(), TimeUnit.SECONDS);
      sweep.commit();
    }

    assertThat(openCause(user.id())).isNull();
    assertAgreesWithTheRule(user.id());
  }

  /**
   * Of two provider rows of one issuer the last in the rule's order decides; changing that order
   * changes the decision and so has to re-evaluate the accounts like a change of the switch.
   */
  @Test
  void reorderingTwoProviderRowsOfOneIssuerReEvaluatesItsAccounts() {
    String issuer = PREFIX + UUID.randomUUID();
    provider("A Anbieter", issuer, true, 0);
    UUID disabled = provider("B Anbieter", " " + issuer, false, 1);
    UUID account = oidcAccount(issuer);
    jdbc.queryForObject(
        "SELECT count(*) FROM (SELECT account_state_reconcile(?, 'ACCOUNT_REACTIVATED', false))"
            + " AS evaluated",
        Integer.class,
        account);
    assertThat(openCause(account)).isEqualTo("PROVIDER_DISABLED");

    jdbc.update("UPDATE oidc_providers SET sort_order = -1 WHERE id = ?", disabled);

    assertThat(openCause(account)).isNull();
    assertAgreesWithTheRule(account);
  }

  /**
   * A handover to a provider writes the issuer and then deletes the credentials; an administrator
   * locks the same account at the same time. Whichever comes first, the account ends a usable
   * account of the provider, and the history must not keep the local lock open.
   */
  @Test
  void aHandoverAndALockOfTheSameAccountLeaveNoLocalLockBehind_handoverFirst() throws Exception {
    Handover handover = handover();
    try (Connection handing = dataSource.getConnection();
        Connection locking = dataSource.getConnection();
        ExecutorService threads = Executors.newSingleThreadExecutor()) {
      handing.setAutoCommit(false);
      locking.setAutoCommit(false);
      int lockingPid = backendPid(locking);
      handOver(handing, handover);
      Future<?> lock = threads.submit(() -> lockByAdministration(locking, handover.account()));
      awaitDoneOrWaiting(lock, lockingPid);
      if (lock.isDone()) {
        lock.get();
        locking.commit();
      }
      update(handing, "DELETE FROM local_credentials WHERE user_id = ?", handover.account());
      handing.commit();
      lock.get(WAIT.toSeconds(), TimeUnit.SECONDS);
      locking.commit();
    }

    assertThat(openCause(handover.account())).isNull();
    assertAgreesWithTheRule(handover.account());
  }

  @Test
  void aHandoverAndALockOfTheSameAccountLeaveNoLocalLockBehind_lockFirst() throws Exception {
    Handover handover = handover();
    try (Connection handing = dataSource.getConnection();
        Connection locking = dataSource.getConnection();
        ExecutorService threads = Executors.newSingleThreadExecutor()) {
      handing.setAutoCommit(false);
      locking.setAutoCommit(false);
      int handingPid = backendPid(handing);
      lockByAdministration(locking, handover.account());
      Future<?> handOver =
          threads.submit(
              () -> {
                handOver(handing, handover);
                return update(
                    handing, "DELETE FROM local_credentials WHERE user_id = ?", handover.account());
              });
      awaitWaitingOnALock(handOver, handingPid);
      locking.commit();
      handOver.get(WAIT.toSeconds(), TimeUnit.SECONDS);
      handing.commit();
    }

    assertThat(openCause(handover.account())).isNull();
    assertAgreesWithTheRule(handover.account());
  }

  // ---- helpers

  record Handover(UUID account, String issuer) {}

  private Handover handover() {
    String issuer = PREFIX + UUID.randomUUID();
    provider("Übernahme", issuer, true, 0);
    LocalAccount user = fixtures.activeUser("uebergabe-" + UUID.randomUUID() + "@stadt.example");
    accounts.add(user.id());
    return new Handover(user.id(), issuer);
  }

  private static Void handOver(Connection connection, Handover handover) {
    try (PreparedStatement statement =
        connection.prepareStatement("UPDATE users SET issuer = ?, subject = ? WHERE id = ?")) {
      statement.setString(1, handover.issuer());
      statement.setString(2, "sub-" + handover.account());
      statement.setObject(3, handover.account());
      statement.executeUpdate();
      return null;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Void lockByAdministration(Connection connection, UUID account) {
    return update(
        connection,
        "UPDATE local_credentials SET locked_at = now(), locked_reason = 'ADMIN'"
            + " WHERE user_id = ?",
        account);
  }

  /** Until the statement has finished or its backend waits on a lock. */
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
    assertThat(statement.isDone()).as("the statement neither finished nor waited").isTrue();
  }

  private UUID provider(String name, String issuer, boolean enabled, int sortOrder) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO oidc_providers (id, display_name, enabled, sort_order, issuer_uri, client_id)"
            + " VALUES (?, ?, ?, ?, ?, 'opaa-frontend')",
        id,
        name,
        enabled,
        sortOrder,
        issuer);
    providers.add(id);
    return id;
  }

  private UUID oidcAccount(String issuer) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, subject, issuer, system_role, organization_id)"
            + " VALUES (?, ?, ?, 'USER', ?)",
        id,
        id.toString(),
        issuer,
        Organization.DEFAULT_ID);
    accounts.add(id);
    return id;
  }

  private String openCause(UUID userId) {
    List<String> causes =
        jdbc.queryForList(
            "SELECT cause FROM account_state_history WHERE user_id = ? AND valid_to IS NULL"
                + " AND state = 'LOCKED'",
            String.class,
            userId);
    return causes.isEmpty() ? null : causes.getFirst();
  }

  private void assertAgreesWithTheRule(UUID userId) {
    assertThat(openCause(userId) != null)
        .as("locked in the history <=> unusable by AccountUsability")
        .isEqualTo(!usability.stateOf(users.findById(userId).orElseThrow()).isUsable());
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

  /** Until the backend of {@code pid} waits on a lock - deterministic, no sleep. */
  private void awaitWaitingOnALock(Future<?> statement, int pid) {
    Instant deadline = Instant.now().plus(WAIT);
    while (Instant.now().isBefore(deadline)) {
      assertThat(statement.isDone())
          .as("the second transaction must wait for the first instead of reading past it")
          .isFalse();
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
    throw new AssertionError("the second transaction never waited");
  }

  private static int backendPid(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()");
        ResultSet result = statement.executeQuery()) {
      result.next();
      return result.getInt(1);
    }
  }
}
