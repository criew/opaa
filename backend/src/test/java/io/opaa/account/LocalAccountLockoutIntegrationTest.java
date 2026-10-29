package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.account.LocalLoginService.AuthenticatedLocalAccount;
import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.LockReason;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The lockout after failed sign-ins through the production {@code oidc} chain against Postgres
 * (ADR-0033, Entscheidungen 9 and 13): the fifth wrong password locks the account for the fixed
 * duration ({@code locked_reason = FAILED_LOGINS}) and resets the counter; attempts during the lock
 * are not counted; the right password is refused during the lock with the very same answer as a
 * wrong one; once the lockout has ended the account has its full budget again and the sign-in
 * succeeds; the lock is audited exactly once under the {@code local-auth} system actor - the
 * attempts themselves never are. Parallel attempts are decided one by one on the locked row: beyond
 * the threshold none is counted, and a right password whose comparison ends after the lock is
 * refused like a wrong one. The rule comes from the table on every attempt (ADR-0060, Entscheidung
 * 17): a change holds from the next attempt without a restart, a running lockout keeps its end, and
 * the limits of the operation bound whatever the table says.
 */
@OpaaLocalAuthMockMvcTest
class LocalAccountLockoutIntegrationTest {

  private static final String LOGIN = "/api/v1/auth/local/login";
  private static final String CLIENT = "10.20.30.40";

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private LocalCredentialsRepository credentials;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private LocalLoginService loginService;
  @Autowired private LocalAuthSettingsService lockoutSettings;
  @Autowired private DataSource dataSource;

  private LocalAccountFixtures fixtures;
  private LocalAccount user;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    fixtures.localProvider(true);
    user = fixtures.activeUser("erika-" + UUID.randomUUID() + "@stadt.example");
  }

  @AfterEach
  void tearDown() {
    jdbc.update(
        "UPDATE local_auth_settings SET lockout_max_attempts = NULL,"
            + " lockout_duration_minutes = NULL");
    fixtures.cleanUp();
  }

  @Test
  void sixWrongPasswordsLockTheAccountAndTheRightOneIsRefusedAlikeUntilTheLockoutEnds()
      throws Exception {
    String wrongPassword = body(login(user.email(), "falsches-passwort"));
    for (int i = 0; i < 3; i++) {
      login(user.email(), "falsches-passwort").andExpect(status().isUnauthorized());
    }
    assertThat(credentials.findById(user.id()).orElseThrow().state(Instant.now()))
        .as("four failed attempts do not lock yet")
        .isEqualTo(LocalAccountState.ACTIVE);

    // the fifth attempt locks
    login(user.email(), "falsches-passwort").andExpect(status().isUnauthorized());
    Instant afterLock = Instant.now();
    LocalCredentials locked = credentials.findById(user.id()).orElseThrow();
    assertThat(locked.state(afterLock)).isEqualTo(LocalAccountState.LOCKED);
    assertThat(locked.getLockedReason()).isEqualTo(LockReason.FAILED_LOGINS);
    assertThat(locked.getLockoutUntil())
        .isBetween(afterLock.plus(Duration.ofMinutes(14)), afterLock.plus(Duration.ofMinutes(16)));
    assertThat(locked.getFailedLoginAttempts()).as("the lock resets the counter").isZero();

    // the sixth wrong password and the right password answer exactly like the first wrong one
    assertThat(body(login(user.email(), "falsches-passwort"))).isEqualTo(wrongPassword);
    assertThat(body(login(user.email(), LocalAccountFixtures.PASSWORD))).isEqualTo(wrongPassword);
    assertThat(wrongPassword).doesNotContain("gesperrt", "locked", "Retry");
    LocalCredentials stillLocked = credentials.findById(user.id()).orElseThrow();
    assertThat(stillLocked.getLockoutUntil())
        .as("attempts during the lockout neither extend it")
        .isEqualTo(locked.getLockoutUntil());
    assertThat(stillLocked.getFailedLoginAttempts())
        .as("attempts during the lockout are not counted")
        .isZero();

    // exactly one audit event, under the system actor, without any personal data
    List<Map<String, Object>> events =
        jdbc.queryForList(
            "SELECT event_type, actor_ref, object_label, subject_ref, before, after, reason"
                + " FROM audit_log WHERE event_type = 'LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS'"
                + " AND "
                + LocalAccountFixtures.NAMES_A_LOCAL_ACCOUNT);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().get("actor_ref")).isEqualTo(LocalRefreshTokenService.SYSTEM_ACTOR);
    assertThat(String.valueOf(events.getFirst().values()))
        .doesNotContain(user.email())
        .doesNotContain(LocalAccountFixtures.DISPLAY_NAME)
        .doesNotContain(user.id().toString())
        .contains("FAILED_LOGINS");

    // the lockout ends by itself: the row's lockout_until is moved into the past - and the
    // account has its full budget again (four more wrong passwords do not lock)
    Instant lockedAt = Instant.now().minus(Duration.ofMinutes(20));
    stillLocked.lock(LockReason.FAILED_LOGINS, lockedAt, lockedAt.plus(Duration.ofMinutes(15)));
    fixtures.save(stillLocked);
    for (int i = 0; i < 4; i++) {
      login(user.email(), "falsches-passwort").andExpect(status().isUnauthorized());
    }
    assertThat(credentials.findById(user.id()).orElseThrow().state(Instant.now()))
        .isEqualTo(LocalAccountState.ACTIVE);

    login(user.email(), LocalAccountFixtures.PASSWORD).andExpect(status().isOk());
    LocalCredentials afterSignIn = credentials.findById(user.id()).orElseThrow();
    assertThat(afterSignIn.getFailedLoginAttempts()).isZero();
    assertThat(afterSignIn.state(Instant.now())).isEqualTo(LocalAccountState.ACTIVE);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log"
                    + " WHERE event_type = 'LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS' AND "
                    + LocalAccountFixtures.NAMES_A_LOCAL_ACCOUNT,
                Long.class))
        .as("the end of a lockout leaves no event")
        .isEqualTo(1L);
  }

  @Test
  void anAdministratorsLockIsNotTurnedIntoAFailedLoginLockByMoreAttempts() throws Exception {
    LocalCredentials row = fixtures.credentialsOf(user);
    row.lock(LockReason.ADMIN, Instant.now(), null);
    fixtures.save(row);

    for (int i = 0; i < 7; i++) {
      login(user.email(), "falsches-passwort").andExpect(status().isUnauthorized());
    }

    LocalCredentials after = credentials.findById(user.id()).orElseThrow();
    assertThat(after.getLockedReason()).isEqualTo(LockReason.ADMIN);
    assertThat(after.getLockoutUntil()).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log"
                    + " WHERE event_type = 'LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS' AND "
                    + LocalAccountFixtures.NAMES_A_LOCAL_ACCOUNT,
                Long.class))
        .isZero();
  }

  /** Written straight into the table, as another instance would: no restart, no instance state. */
  @Test
  void aRuleChangedInTheTableHoldsFromTheNextAttempt() throws Exception {
    lockoutRule(2, 60);

    login(user.email(), "falsches-passwort").andExpect(status().isUnauthorized());
    assertThat(credentials.findById(user.id()).orElseThrow().state(Instant.now()))
        .isEqualTo(LocalAccountState.ACTIVE);
    login(user.email(), "falsches-passwort").andExpect(status().isUnauthorized());

    Instant afterLock = Instant.now();
    LocalCredentials locked = credentials.findById(user.id()).orElseThrow();
    assertThat(locked.state(afterLock)).isEqualTo(LocalAccountState.LOCKED);
    assertThat(locked.getLockoutUntil())
        .isBetween(afterLock.plus(Duration.ofMinutes(59)), afterLock.plus(Duration.ofMinutes(61)));
  }

  /**
   * A change of the rule moves no running lockout, and an account whose counter already reaches a
   * lowered threshold is locked by its next failed attempt, not by the change itself.
   */
  @Test
  void aChangedRuleKeepsRunningLockoutsAndLocksOnlyAtTheNextAttempt() throws Exception {
    LocalAccount other = fixtures.activeUser("max-" + UUID.randomUUID() + "@stadt.example");
    for (int i = 0; i < 5; i++) {
      login(other.email(), "falsches-passwort").andExpect(status().isUnauthorized());
    }
    Instant runningUntil = credentials.findById(other.id()).orElseThrow().getLockoutUntil();
    assertThat(runningUntil).isNotNull();
    for (int i = 0; i < 3; i++) {
      login(user.email(), "falsches-passwort").andExpect(status().isUnauthorized());
    }

    lockoutRule(2, 120);

    assertThat(credentials.findById(other.id()).orElseThrow().getLockoutUntil())
        .as("a running lockout keeps its end")
        .isEqualTo(runningUntil);
    LocalCredentials beforeNextAttempt = credentials.findById(user.id()).orElseThrow();
    assertThat(beforeNextAttempt.state(Instant.now())).isEqualTo(LocalAccountState.ACTIVE);
    assertThat(beforeNextAttempt.getFailedLoginAttempts()).isEqualTo(3);

    login(user.email(), "falsches-passwort").andExpect(status().isUnauthorized());

    Instant afterLock = Instant.now();
    LocalCredentials locked = credentials.findById(user.id()).orElseThrow();
    assertThat(locked.state(afterLock)).isEqualTo(LocalAccountState.LOCKED);
    assertThat(locked.getLockoutUntil())
        .isBetween(
            afterLock.plus(Duration.ofMinutes(119)), afterLock.plus(Duration.ofMinutes(121)));
  }

  /** A value below the limit of the operation, stored before it was raised, locks as the limit. */
  @Test
  void theLimitsOfTheOperationBoundAValueStoredBeyondThem() throws Exception {
    lockoutRule(1, 1);

    login(user.email(), "falsches-passwort").andExpect(status().isUnauthorized());

    Instant afterLock = Instant.now();
    LocalCredentials locked = credentials.findById(user.id()).orElseThrow();
    assertThat(locked.state(afterLock)).isEqualTo(LocalAccountState.LOCKED);
    assertThat(locked.getLockoutUntil())
        .as("the shortest lockout of the operation, five minutes in this context")
        .isBetween(afterLock.plus(Duration.ofMinutes(4)), afterLock.plus(Duration.ofMinutes(6)));
  }

  private void lockoutRule(int attempts, int minutes) {
    jdbc.update(
        "UPDATE local_auth_settings SET lockout_max_attempts = ?, lockout_duration_minutes = ?",
        attempts,
        minutes);
  }

  /**
   * Far more parallel wrong passwords than the threshold: exactly one lock, and none of the
   * attempts decided after it is counted - the counter the lock reset stays at zero.
   */
  @Test
  void parallelWrongPasswordsBeyondTheThresholdLockOnceAndCountNothingAfterTheLock()
      throws Exception {
    // more threads than the connection pool holds: the hash comparison needs no connection, and
    // the deciding transactions queue for the row anyway
    int attempts = lockoutSettings.lockoutPolicy().maxAttempts() * 4;
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
      List<Future<Optional<AuthenticatedLocalAccount>>> results = new ArrayList<>();
      for (int i = 0; i < attempts; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return loginService.authenticate(user.email(), "falsches-passwort", CLIENT);
                }));
      }
      start.countDown();
      for (Future<Optional<AuthenticatedLocalAccount>> result : results) {
        assertThat(result.get(60, TimeUnit.SECONDS)).isEmpty();
      }
    }

    LocalCredentials after = credentials.findById(user.id()).orElseThrow();
    assertThat(after.state(Instant.now())).isEqualTo(LocalAccountState.LOCKED);
    assertThat(after.getLockedReason()).isEqualTo(LockReason.FAILED_LOGINS);
    assertThat(after.getFailedLoginAttempts())
        .as("no attempt decided after the lock is counted")
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log"
                    + " WHERE event_type = 'LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS' AND "
                    + LocalAccountFixtures.NAMES_A_LOCAL_ACCOUNT,
                Long.class))
        .isEqualTo(1L);
  }

  /**
   * The right password read the account as active, and a parallel attempt locks it while the hash
   * is compared: the sign-in is decided on the row as it stands after the lock, so it is refused
   * like a wrong password and reveals nothing the attacker could use once the lockout has ended.
   */
  @Test
  void aRightPasswordWhoseComparisonEndsAfterAParallelLockIsRefused() throws Exception {
    try (ExecutorService pool = Executors.newSingleThreadExecutor();
        Connection parallelAttempt = dataSource.getConnection()) {
      Future<Optional<AuthenticatedLocalAccount>> signIn;
      parallelAttempt.setAutoCommit(false);
      int holderPid = holdRowLock(parallelAttempt, user.id());
      signIn =
          pool.submit(
              () -> loginService.authenticate(user.email(), LocalAccountFixtures.PASSWORD, CLIENT));
      // the sign-in has read the row and compared the hash: it now waits for the row - or, deciding
      // on what it read before, it has already finished
      await().atMost(Duration.ofSeconds(30)).until(() -> signIn.isDone() || waitsBehind(holderPid));
      try (PreparedStatement lock =
          parallelAttempt.prepareStatement(
              "UPDATE local_credentials SET locked_reason = 'FAILED_LOGINS',"
                  + " locked_at = clock_timestamp(),"
                  + " lockout_until = clock_timestamp() + interval '15 minutes',"
                  + " failed_login_attempts = 0, version = version + 1 WHERE user_id = ?")) {
        lock.setObject(1, user.id());
        assertThat(lock.executeUpdate()).isEqualTo(1);
      }
      parallelAttempt.commit();
      assertThat(signIn.get(60, TimeUnit.SECONDS))
          .as("a right password decided after the lock is refused like a wrong one")
          .isEmpty();
    }
    assertThat(credentials.findById(user.id()).orElseThrow().state(Instant.now()))
        .isEqualTo(LocalAccountState.LOCKED);
  }

  private static int holdRowLock(Connection connection, UUID userId) throws Exception {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT pg_backend_pid() FROM local_credentials WHERE user_id = ? FOR UPDATE")) {
      select.setObject(1, userId);
      try (ResultSet rows = select.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getInt(1);
      }
    }
  }

  private boolean waitsBehind(int holderPid) {
    Integer waiting =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
            Integer.class,
            holderPid);
    return waiting != null && waiting > 0;
  }

  private ResultActions login(String email, String password) throws Exception {
    return mockMvc.perform(
        post(LOGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
  }

  /** The body with the timestamp removed, so two answers can be compared for equality. */
  private static String body(ResultActions actions) throws Exception {
    return actions
        .andExpect(status().isUnauthorized())
        .andReturn()
        .getResponse()
        .getContentAsString()
        .replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"-\"");
  }
}
