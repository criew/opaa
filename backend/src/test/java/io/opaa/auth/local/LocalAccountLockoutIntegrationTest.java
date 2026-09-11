package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.LockReason;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * attempts themselves never are.
 */
@OpaaLocalAuthMockMvcTest
class LocalAccountLockoutIntegrationTest {

  private static final String LOGIN = "/api/v1/auth/local/login";

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private LocalCredentialsRepository credentials;
  @Autowired private JdbcTemplate jdbc;

  private LocalAccountFixtures fixtures;
  private LocalAccount user;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    fixtures.localProvider(true);
    user = fixtures.activeUser("erika-" + UUID.randomUUID() + "@stadt.example");
    jdbc.update(
        "DELETE FROM audit_log WHERE event_type = 'LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS'");
  }

  @AfterEach
  void tearDown() {
    fixtures.cleanUp();
    jdbc.update(
        "DELETE FROM audit_log WHERE event_type = 'LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS'");
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
                + " FROM audit_log WHERE event_type = 'LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS'");
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
                    + " WHERE event_type = 'LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS'",
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
                    + " WHERE event_type = 'LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS'",
                Long.class))
        .isZero();
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
