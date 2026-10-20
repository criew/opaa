package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.LocalAccountState;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.TwoInstanceRace;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two instances run the steps of the daily local-account run at the same moment (ADR-0053,
 * Entscheidung 7): the scheduled-run lock keeps that from happening, but a run outliving its lock
 * must not double what it sends or records. Every lock, reminder and review mail goes out once, and
 * every audit entry stands in the log once.
 */
@OpaaIntegrationTest
class LocalAccountMaintenanceTwoInstanceIntegrationTest {

  @Autowired private InactivityLockStep inactivityLock;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private LocalCredentialsRepository credentials;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;

  private LocalAccountFixtures fixtures;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    // a second administrator, so that no account of this class is the last login-capable one
    fixtures.activeAdmin("verwaltung-" + UUID.randomUUID() + "@stadt.example");
  }

  @AfterEach
  void tearDown() {
    fixtures.cleanUp();
  }

  @Test
  void twoInstancesLockAnInactiveAccountOnce() throws Exception {
    Instant now = Instant.now();
    LocalAccount idle = fixtures.activeUser("ruhend-" + UUID.randomUUID() + "@stadt.example");
    jdbc.update(
        "UPDATE users SET last_login_at = ? WHERE id = ?",
        Timestamp.from(now.minus(Duration.ofDays(400))),
        idle.id());

    new TwoInstanceRace(dataSource)
        .run(
            () -> inactivityLock.run(now),
            () -> inactivityLock.run(now),
            "SELECT 1 FROM local_credentials WHERE user_id = ? FOR UPDATE",
            idle.id());

    assertThat(credentials.findById(idle.id()).orElseThrow().state(now))
        .isEqualTo(LocalAccountState.LOCKED);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE event_type = 'LOCAL_USER_LOCKED'"
                    + " AND subject_ref IN (SELECT CAST(pseudonym_id AS text)"
                    + " FROM audit_actor_pseudonyms WHERE user_id = ?)",
                Long.class,
                idle.id()))
        .isEqualTo(1L);
  }
}
