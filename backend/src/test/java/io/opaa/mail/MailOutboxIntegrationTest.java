package io.opaa.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import io.opaa.api.types.MailEncryption;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The mail outbox against a real database and a real SMTP server (ADR-0053, Entscheidung 11). A
 * further "instance" is a {@link MailOutboxWorker} built here against the same database - what a
 * second backend process shares with this one is exactly the table; the context's own worker keeps
 * running alongside and competes for every row, as a second process would.
 */
@OpaaIntegrationTest
class MailOutboxIntegrationTest {

  private static final String RECIPIENT = "erika.mustermann@amt.example";
  private static final Duration SHORT_LEASE = Duration.ofSeconds(2);

  @Autowired private MailService mailService;
  @Autowired private MailOutbox outbox;
  @Autowired private MailSenderProvider senderProvider;
  @Autowired private MailSettingsService mailSettingsService;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;

  private GreenMail greenMail;
  private UUID organizationId;
  private UUID userId;
  private final List<UUID> rowIds = new ArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    rowIds.clear();
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Outbox Org")).getId();
    User user = new User(UUID.randomUUID().toString(), "test-issuer", "ob@example.com", "Test");
    user.setOrganizationId(organizationId);
    userId = userRepository.save(user).getId();
    greenMail = new GreenMail(new ServerSetup(freePort(), "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
    greenMail.setUser("opaa@intern.example", "kennung", "geheim");
    greenMail.start();
  }

  @AfterEach
  void tearDown() {
    greenMail.stop();
    mailSettingsService.updateSettings(
        organizationId,
        userId,
        new MailSettingsUpdate(false, null, null, null, "", MailEncryption.STARTTLS, null, null));
    for (UUID id : rowIds) {
      jdbcTemplate.update("DELETE FROM mail_outbox WHERE id = ?", id);
    }
    jdbcTemplate.update(
        "UPDATE mail_settings SET last_success_at = NULL, last_failure_at = NULL,"
            + " last_failure_reason = NULL WHERE id = 1");
    mailSettingsService.resetCaches();
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    userRepository.deleteById(userId);
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void aMailQueuedInARolledBackTransactionIsNeverDelivered() {
    configureSmtp(greenMail.getSmtp().getPort());

    transactionTemplate.executeWithoutResult(
        status -> {
          assertThat(enqueueTestMail()).isTrue();
          status.setRollbackOnly();
        });

    assertThat(greenMail.waitForIncomingEmail(3_000, 1)).isFalse();
  }

  @Test
  void aCommittedMailLeavesWithItsCommitUnderTheIdOfItsRow() throws Exception {
    configureSmtp(greenMail.getSmtp().getPort());

    transactionTemplate.executeWithoutResult(status -> assertThat(enqueueTestMail()).isTrue());

    assertThat(greenMail.waitForIncomingEmail(10_000, 1)).isTrue();
    MimeMessage received = greenMail.getReceivedMessages()[0];
    UUID rowId = rowIdOf(received);
    rowIds.add(rowId);
    assertThat(received.getAllRecipients()[0].toString()).isEqualTo(RECIPIENT);
    await().atMost(Duration.ofSeconds(10)).until(() -> !exists(rowId));
  }

  /**
   * The acceptance criterion "a mail requested before an instance stops is delivered exactly once
   * after the restart": instance A commits the mail and claims it for delivery, then stops without
   * delivering - nothing of the mail lives in its memory. A restarted instance delivers it once its
   * claim has lapsed, and only once although the context's own worker competes for it.
   */
  @Test
  void aMailRequestedBeforeAnInstanceStopsIsDeliveredExactlyOnceAfterTheRestart() throws Exception {
    configureSmtp(greenMail.getSmtp().getPort());
    UUID rowId =
        transactionTemplate.execute(
            status -> {
              assertThat(enqueueTestMail()).isTrue();
              MailOutbox.Batch claimedByA = outbox.claimDue(1, SHORT_LEASE);
              assertThat(claimedByA.claimed()).hasSize(1);
              return claimedByA.claimed().getFirst().id();
            });
    rowIds.add(rowId);
    // instance A stops here: it never delivers and never releases its claim
    assertThat(greenMail.waitForIncomingEmail(1_000, 1)).isFalse();

    MailOutboxWorker restarted = workerOfAnotherInstance();
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .until(
            () -> {
              restarted.deliverDue();
              return !exists(rowId);
            });

    assertThat(greenMail.waitForIncomingEmail(10_000, 1)).isTrue();
    assertThat(greenMail.waitForIncomingEmail(2_000, 2)).isFalse();
    assertThat(rowIdOf(greenMail.getReceivedMessages()[0])).isEqualTo(rowId);
  }

  /** Two further instances and the context's own worker drain the same rows: each leaves once. */
  @Test
  void workersOfSeveralInstancesDeliverEveryMailExactlyOnce() throws Exception {
    int mails = 6;
    configureSmtp(greenMail.getSmtp().getPort());
    MailOutboxWorker instanceB = workerOfAnotherInstance();
    MailOutboxWorker instanceC = workerOfAnotherInstance();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService threads = Executors.newFixedThreadPool(2);
    try {
      Future<?> b = threads.submit(() -> awaitThenDeliver(start, instanceB));
      Future<?> c = threads.submit(() -> awaitThenDeliver(start, instanceC));
      transactionTemplate.executeWithoutResult(
          status -> {
            for (int i = 0; i < mails; i++) {
              assertThat(enqueueTestMail()).isTrue();
            }
          });
      start.countDown();
      b.get();
      c.get();
    } finally {
      threads.shutdownNow();
    }

    assertThat(greenMail.waitForIncomingEmail(20_000, mails)).isTrue();
    assertThat(greenMail.waitForIncomingEmail(3_000, mails + 1)).isFalse();
    List<UUID> delivered =
        Arrays.stream(greenMail.getReceivedMessages())
            .map(MailOutboxIntegrationTest::rowIdOf)
            .toList();
    rowIds.addAll(delivered);
    assertThat(delivered).hasSize(mails).doesNotHaveDuplicates();
    await().atMost(Duration.ofSeconds(10)).until(() -> delivered.stream().noneMatch(this::exists));
  }

  /**
   * A refusal leaves the row waiting with a growing delay and the cause without the address; after
   * the last attempt the row goes.
   */
  @Test
  void aRefusedDeliveryWaitsWithAMaskedReasonAndIsDroppedAfterTheLastAttempt() throws Exception {
    configureSmtp(freePort());
    RenderedMail mail = new RenderedMail("Betreff", "Text", null);
    UUID rowId = outbox.insert(MailTemplateKey.TEST_MAIL, RECIPIENT, mail);
    rowIds.add(rowId);
    MailOutboxWorker worker = workerOfAnotherInstance();

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .until(
            () -> {
              worker.deliverDue();
              return attemptsOf(rowId) == 1 && reasonOf(rowId) != null;
            });
    assertThat(reasonOf(rowId)).doesNotContain(RECIPIENT).doesNotContain("erika.mustermann");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT next_attempt_at > now() + interval '50 seconds' FROM mail_outbox"
                    + " WHERE id = ?",
                Boolean.class,
                rowId))
        .as("the second attempt waits for the first retry delay")
        .isTrue();

    jdbcTemplate.update(
        "UPDATE mail_outbox SET attempts = ?, next_attempt_at = now() WHERE id = ?",
        MailOutboxWorker.MAX_ATTEMPTS - 1,
        rowId);
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .until(
            () -> {
              worker.deliverDue();
              return !exists(rowId);
            });
    assertThat(greenMail.getReceivedMessages()).isEmpty();
  }

  private boolean enqueueTestMail() {
    return mailService.enqueue(
        MailTemplateKey.TEST_MAIL,
        Locale.GERMAN,
        RECIPIENT,
        Map.of("displayName", "Erika Mustermann", "occurredAtHuman", "am 11.09.2026"));
  }

  private MailOutboxWorker workerOfAnotherInstance() {
    return new MailOutboxWorker(
        outbox,
        mailService,
        senderProvider,
        new ConcurrentTaskScheduler(),
        java.time.Clock.systemUTC(),
        SHORT_LEASE);
  }

  private static void awaitThenDeliver(CountDownLatch start, MailOutboxWorker worker) {
    try {
      start.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    worker.deliverDue();
  }

  private boolean exists(UUID rowId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM mail_outbox WHERE id = ?", Integer.class, rowId);
    return count != null && count > 0;
  }

  private int attemptsOf(UUID rowId) {
    List<Integer> attempts =
        jdbcTemplate.queryForList(
            "SELECT attempts FROM mail_outbox WHERE id = ?", Integer.class, rowId);
    return attempts.isEmpty() ? -1 : attempts.getFirst();
  }

  private String reasonOf(UUID rowId) {
    List<String> reasons =
        jdbcTemplate.queryForList(
            "SELECT last_failure_reason FROM mail_outbox WHERE id = ?", String.class, rowId);
    return reasons.isEmpty() ? null : reasons.getFirst();
  }

  private static UUID rowIdOf(MimeMessage message) {
    try {
      String messageId = message.getMessageID();
      assertThat(messageId).matches("<[0-9a-f-]{36}@opaa>");
      return UUID.fromString(messageId.substring(1, messageId.indexOf('@')));
    } catch (jakarta.mail.MessagingException e) {
      throw new IllegalStateException(e);
    }
  }

  private void configureSmtp(int port) {
    mailSettingsService.updateSettings(
        organizationId,
        userId,
        new MailSettingsUpdate(
            true,
            "127.0.0.1",
            port,
            "kennung",
            "geheim",
            MailEncryption.NONE,
            "opaa@intern.example",
            "OPAA"));
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
