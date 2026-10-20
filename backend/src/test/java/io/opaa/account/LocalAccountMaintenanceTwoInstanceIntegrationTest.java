package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.LocalAccountState;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.TwoInstanceRace;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two instances run the steps of the daily local-account run at the same moment (ADR-0053,
 * Entscheidung 7): the scheduled-run lock keeps that from happening, but a run outliving its lock
 * must not double what it sends or records. Every lock, reminder and review mail goes out once, and
 * every audit entry stands in the log once. The steps are built with a mailer that counts what it
 * is asked to send, in the transaction of the step, as the outbox would receive it.
 */
@OpaaIntegrationTest
class LocalAccountMaintenanceTwoInstanceIntegrationTest {

  private static final ZoneId ZONE = ZoneId.systemDefault();

  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private LocalCredentialsRepository credentials;
  @Autowired private UserRepository users;
  @Autowired private LocalAuthSettingsRepository settings;
  @Autowired private LocalUserService accounts;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Clock clock;

  private final CountingMailer mailer = new CountingMailer();
  private LocalAccountFixtures fixtures;
  private LocalAccount admin;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    // a second administrator, so that no account of this class is the last login-capable one
    admin = fixtures.activeAdmin("verwaltung-" + UUID.randomUUID() + "@stadt.example");
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
    InactivityLockStep step =
        new InactivityLockStep(users, credentials, settings, accounts, mailer);

    new TwoInstanceRace(dataSource)
        .run(
            () -> step.run(now),
            () -> step.run(now),
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
    assertThat(mailer.sent("locked", idle.id())).isEqualTo(1);
  }

  @Test
  void twoInstancesRemindOfAnExpiryOnce() throws Exception {
    Instant now = Instant.now();
    LocalAccount soon = fixtures.activeUser("bald-" + UUID.randomUUID() + "@stadt.example");
    expiresAt(soon, ExpiryReminderStep.windowOf(now, ZONE).from().plusSeconds(3600));
    ExpiryReminderStep step = expiryStep();

    new TwoInstanceRace(dataSource)
        .run(
            () -> step.run(now),
            () -> step.run(now),
            "SELECT 1 FROM local_credentials WHERE user_id = ? FOR UPDATE",
            soon.id());

    assertThat(mailer.sent("expiring", soon.id())).isEqualTo(1);

    // a moved expiry is a new one and is reminded of again
    expiresAt(soon, ExpiryReminderStep.windowOf(now, ZONE).from().plusSeconds(7200));
    step.run(now);
    assertThat(mailer.sent("expiring", soon.id())).isEqualTo(2);
  }

  @Test
  void twoInstancesSendTheQuarterlyReviewReminderOnce() throws Exception {
    Instant quarterStart = ZonedDateTime.of(2026, 10, 1, 3, 20, 0, 0, ZONE).toInstant();
    LocalAccount soon = fixtures.activeUser("bald-" + UUID.randomUUID() + "@stadt.example");
    expiresAt(soon, ExpiryReminderStep.windowOf(quarterStart, ZONE).from().plusSeconds(3600));
    // the day before, so that the race meets an existing row both instances want to claim
    jdbc.update(
        "INSERT INTO admin_review_reminders (organization_id, reminded_on) VALUES (?, ?)"
            + " ON CONFLICT (organization_id) DO UPDATE SET reminded_on = EXCLUDED.reminded_on",
        Organization.DEFAULT_ID,
        Date.valueOf(LocalDate.of(2026, 9, 30)));
    AdminReviewReminderStep step =
        new AdminReviewReminderStep(
            users, credentials, expiryStep(), mailer, jdbc, transactionManager, clock);

    new TwoInstanceRace(dataSource)
        .run(
            () -> step.run(quarterStart),
            () -> step.run(quarterStart),
            "SELECT 1 FROM admin_review_reminders WHERE organization_id = ? FOR UPDATE",
            Organization.DEFAULT_ID);

    assertThat(mailer.sent("review", admin.id())).isEqualTo(1);
  }

  private ExpiryReminderStep expiryStep() {
    return new ExpiryReminderStep(
        credentials,
        users,
        mailer,
        new TransactionTemplate(transactionManager),
        ZONE,
        ExpiryReminderStep.TIME_BUDGET);
  }

  private void expiresAt(LocalAccount account, Instant at) {
    LocalCredentials row = fixtures.credentialsOf(account);
    row.setExpiresAt(at, Instant.now());
    fixtures.save(row);
  }

  /** Counts each mail by kind and recipient instead of sending it. */
  private static final class CountingMailer extends LocalAccountMailer {

    private final Map<String, Integer> sent = new ConcurrentHashMap<>();

    private CountingMailer() {
      super(null, null, Clock.systemDefaultZone());
    }

    int sent(String kind, UUID userId) {
      return sent.getOrDefault(kind + ":" + userId, 0);
    }

    private void count(String kind, User user) {
      sent.merge(kind + ":" + user.getId(), 1, Integer::sum);
    }

    @Override
    public void sendExpiring(User user, Instant expiresAt) {
      count("expiring", user);
    }

    @Override
    public void sendReviewReminder(User admin, long count) {
      count("review", admin);
    }

    @Override
    public void sendLockedForInactivity(User user, int inactiveDays) {
      count("locked", user);
    }
  }
}
