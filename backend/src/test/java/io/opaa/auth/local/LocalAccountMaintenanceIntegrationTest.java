package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.LockReason;
import io.opaa.api.types.MailEncryption;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.mail.MailSettingsService;
import io.opaa.mail.MailSettingsUpdate;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaIntegrationTest;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.IOException;
import java.net.ServerSocket;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The three steps the daily run drives after the token cleanup (ADR-0033, Entscheidungen 7, 9 and
 * 11): the lock after the inactivity period - never the bootstrap account, never the last
 * login-capable system administrator -, the reminder fourteen days before an expiry to the person
 * and the system administrators, and the quarterly review reminder with the number of accounts
 * without an expiry date and no names. Every step takes the instant of the run as a parameter, so
 * the windows are proved with chosen instants against the shared clock-free fixtures.
 */
@OpaaIntegrationTest
class LocalAccountMaintenanceIntegrationTest {

  @Autowired private InactivityLockStep inactivityLock;
  @Autowired private ExpiryReminderStep expiryReminder;
  @Autowired private QuarterlyReviewReminderStep quarterlyReminder;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private LocalCredentialsRepository credentials;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private MailSettingsService mailSettings;
  @Autowired private JdbcTemplate jdbc;

  private LocalAccountFixtures fixtures;
  private GreenMail greenMail;
  private LocalAccount admin;

  @BeforeEach
  void setUp() throws IOException {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    jdbc.update(
        "DELETE FROM audit_log WHERE event_type IN ('LOCAL_USER_LOCKED', 'LOCAL_SESSION_REVOKED')");
    admin = fixtures.activeAdmin("verwaltung-" + UUID.randomUUID() + "@stadt.example");
    greenMail = new GreenMail(new ServerSetup(freePort(), "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
    greenMail.setUser("opaa@intern.example", "kennung", "geheim");
    greenMail.start();
    mailSettings.updateSettings(
        Organization.DEFAULT_ID,
        admin.id(),
        new MailSettingsUpdate(
            true,
            "127.0.0.1",
            greenMail.getSmtp().getPort(),
            "kennung",
            "geheim",
            MailEncryption.NONE,
            "opaa@intern.example",
            "OPAA"));
  }

  @AfterEach
  void tearDown() {
    greenMail.stop();
    mailSettings.updateSettings(
        Organization.DEFAULT_ID,
        admin.id(),
        new MailSettingsUpdate(false, null, null, null, "", MailEncryption.STARTTLS, null, null));
    mailSettings.resetCaches();
    jdbc.update(
        "DELETE FROM audit_log WHERE event_type IN ('LOCAL_USER_LOCKED', 'LOCAL_SESSION_REVOKED')");
    fixtures.cleanUp();
  }

  @Test
  void locksAccountsWithoutActivityForTheInactivityPeriodButNeverTheBootstrapOrTheLastAdmin()
      throws Exception {
    Instant now = Instant.now();
    LocalAccount idle = fixtures.activeUser("ruhend-" + UUID.randomUUID() + "@stadt.example");
    LocalAccount recent = fixtures.activeUser("aktiv-" + UUID.randomUUID() + "@stadt.example");
    LocalAccount neverUsedButNew =
        fixtures.activeUser("neu-" + UUID.randomUUID() + "@stadt.example");
    LocalAccount invited =
        fixtures.invitedUser("eingeladen-" + UUID.randomUUID() + "@stadt.example");
    LocalAccount bootstrap =
        fixtures.activeAdmin("notanker-" + UUID.randomUUID() + "@stadt.example");
    LocalCredentials bootstrapRow = fixtures.credentialsOf(bootstrap);
    bootstrapRow.markBootstrap();
    fixtures.save(bootstrapRow);
    // an organization of its own, so the one administrator in it is the last login-capable one
    UUID lonelyOrganization =
        organizations.save(new Organization(UUID.randomUUID(), "Allein")).getId();
    User lonelyAdmin = localAdmin(lonelyOrganization);
    lastActivity(idle.id(), now.minus(Duration.ofDays(91)));
    lastActivity(recent.id(), now.minus(Duration.ofDays(88)));
    lastActivity(neverUsedButNew.id(), null);
    lastActivity(invited.id(), now.minus(Duration.ofDays(400)));
    lastActivity(bootstrap.id(), now.minus(Duration.ofDays(400)));
    lastActivity(lonelyAdmin.getId(), now.minus(Duration.ofDays(400)));
    ListAppender<ILoggingEvent> warnings = attachTo(InactivityLockStep.class);

    try {
      inactivityLock.run(now);

      LocalCredentials locked = credentials.findById(idle.id()).orElseThrow();
      assertThat(locked.state(now)).isEqualTo(LocalAccountState.LOCKED);
      assertThat(locked.getLockedReason()).isEqualTo(LockReason.INACTIVITY);
      assertThat(locked.getPasswordInvalidatedBefore()).isNotNull();
      assertThat(credentials.findById(recent.id()).orElseThrow().state(now))
          .isEqualTo(LocalAccountState.ACTIVE);
      assertThat(credentials.findById(neverUsedButNew.id()).orElseThrow().state(now))
          .isEqualTo(LocalAccountState.ACTIVE);
      assertThat(credentials.findById(invited.id()).orElseThrow().state(now))
          .isEqualTo(LocalAccountState.INVITED);
      assertThat(credentials.findById(bootstrap.id()).orElseThrow().state(now))
          .isEqualTo(LocalAccountState.ACTIVE);
      assertThat(credentials.findById(lonelyAdmin.getId()).orElseThrow().state(now))
          .isEqualTo(LocalAccountState.ACTIVE);
      assertThat(warnings.list)
          .anyMatch(
              event ->
                  event.getLevel() == Level.WARN
                      && event.getFormattedMessage().contains(lonelyAdmin.getId().toString())
                      && !event.getFormattedMessage().contains(lonelyAdmin.getEmail()));

      List<String> lockedEvents =
          jdbc.queryForList(
              "SELECT CAST(after AS text) FROM audit_log WHERE event_type = 'LOCAL_USER_LOCKED'",
              String.class);
      assertThat(lockedEvents).hasSize(1);
      assertThat(lockedEvents.getFirst()).contains("INACTIVITY");
      assertThat(greenMail.waitForIncomingEmail(10_000, 1)).isTrue();
      MimeMessage mail = greenMail.getReceivedMessages()[0];
      assertThat(mail.getAllRecipients()[0].toString()).isEqualTo(idle.email());
      assertThat(mail.getSubject()).contains("gesperrt");

      // a second run changes nothing: the account is already locked
      inactivityLock.run(now.plus(Duration.ofDays(1)));
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM audit_log WHERE event_type = 'LOCAL_USER_LOCKED'",
                  Long.class))
          .isEqualTo(1L);
    } finally {
      jdbc.update("DELETE FROM audit_log WHERE organization_id = ?", lonelyOrganization);
      credentials.deleteById(lonelyAdmin.getId());
      users.deleteById(lonelyAdmin.getId());
      organizations.deleteById(lonelyOrganization);
    }
  }

  @Test
  void remindsThePersonAndTheAdministratorsFourteenDaysBeforeAnExpiryExactlyOnce()
      throws Exception {
    Instant now = Instant.now();
    LocalAccount soon = fixtures.activeUser("bald-" + UUID.randomUUID() + "@stadt.example");
    LocalAccount later = fixtures.activeUser("spaeter-" + UUID.randomUUID() + "@stadt.example");
    LocalAccount unlimited =
        fixtures.activeUser("unbefristet-" + UUID.randomUUID() + "@stadt.example");
    expiresAt(soon, now.plus(Duration.ofDays(13)).plus(Duration.ofHours(12)));
    expiresAt(later, now.plus(Duration.ofDays(20)));

    expiryReminder.run(now);

    assertThat(greenMail.waitForIncomingEmail(10_000, 2)).isTrue();
    List<String> recipients =
        Arrays.stream(greenMail.getReceivedMessages())
            .map(
                message -> {
                  try {
                    return message.getAllRecipients()[0].toString();
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                })
            .toList();
    // the person and every system administrator with an address (the shared context may hold
    // further administrators); never the accounts outside the window
    assertThat(recipients)
        .contains(soon.email(), admin.email())
        .doesNotContain(later.email(), unlimited.email());
    for (MimeMessage message : greenMail.getReceivedMessages()) {
      String body = plainText(message);
      assertThat(body).doesNotContain(later.email()).doesNotContain(unlimited.email());
    }
    int afterFirstRun = greenMail.getReceivedMessages().length;

    // the next day's run lies outside the window: no second reminder
    expiryReminder.run(now.plus(Duration.ofDays(1)));
    Thread.sleep(300);
    assertThat(greenMail.getReceivedMessages()).hasSize(afterFirstRun);
  }

  @Test
  void remindsTheAdministratorsOnTheFirstDayOfAQuarterWithTheCountAndNoNames() throws Exception {
    LocalAccount one = fixtures.activeUser("eins-" + UUID.randomUUID() + "@stadt.example");
    LocalAccount two = fixtures.activeUser("zwei-" + UUID.randomUUID() + "@stadt.example");
    LocalAccount limited = fixtures.activeUser("befristet-" + UUID.randomUUID() + "@stadt.example");
    expiresAt(limited, Instant.now().plus(Duration.ofDays(30)));
    Instant quarterStart = Instant.parse("2026-10-01T03:20:00Z");

    quarterlyReminder.run(quarterStart.plus(Duration.ofDays(1)));
    Thread.sleep(300);
    assertThat(greenMail.getReceivedMessages()).isEmpty();

    quarterlyReminder.run(quarterStart);

    assertThat(greenMail.waitForIncomingEmail(10_000, 1)).isTrue();
    MimeMessage mail =
        Arrays.stream(greenMail.getReceivedMessages())
            .filter(
                message -> {
                  try {
                    return admin.email().equals(message.getAllRecipients()[0].toString());
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                })
            .findFirst()
            .orElseThrow();
    String body = plainText(mail);
    // the admin itself, one and two carry no expiry date - the count says three
    assertThat(body).contains("3 Zug");
    // the greeting names the recipient; the counted accounts stay unnamed
    assertThat(body)
        .doesNotContain(one.email())
        .doesNotContain(two.email())
        .doesNotContain(limited.email())
        .doesNotContain(one.id().toString())
        .doesNotContain(two.id().toString());
  }

  private User localAdmin(UUID organizationId) {
    User user = User.localAccount("allein-" + UUID.randomUUID() + "@stadt.example", "Allein");
    user.setOrganizationId(organizationId);
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    User saved = users.save(user);
    Instant now = Instant.now();
    LocalCredentials row = new LocalCredentials(saved.getId(), "Testkonto", now);
    row.markEmailVerified(now);
    row.setPasswordHash(passwordEncoder.encode("irrelevant"), now);
    credentials.save(row);
    return saved;
  }

  private void lastActivity(UUID userId, Instant at) {
    jdbc.update(
        "UPDATE users SET last_login_at = ? WHERE id = ?",
        at == null ? null : Timestamp.from(at),
        userId);
  }

  private void expiresAt(LocalAccount account, Instant at) {
    LocalCredentials row = fixtures.credentialsOf(account);
    row.setExpiresAt(at, Instant.now());
    fixtures.save(row);
  }

  private static String plainText(MimeMessage message) throws Exception {
    Object content = message.getContent();
    if (content instanceof MimeMultipart multipart) {
      for (int i = 0; i < multipart.getCount(); i++) {
        Part part = multipart.getBodyPart(i);
        if (part.isMimeType("text/plain")) {
          return part.getContent().toString();
        }
      }
    }
    return content.toString();
  }

  private static ListAppender<ILoggingEvent> attachTo(Class<?> type) {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(type)).addAppender(appender);
    return appender;
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
