package io.opaa.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import io.opaa.api.types.MailEncryption;
import io.opaa.test.OpaaIntegrationTest;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The check of SMTP settings that are not stored, against a real SMTP server (GreenMail, in-JVM):
 * it connects and signs in without sending anything, sends the test message only to the address it
 * is given, and leaves the stored settings and their send status as they were.
 */
@OpaaIntegrationTest
class MailConnectionTesterIntegrationTest {

  private static final String SMTP_USER = "kennung";
  private static final String SMTP_PASSWORD = "geheimesKennwort";
  private static final String RECIPIENT = "it-betrieb@stadt.example";

  @Autowired private MailConnectionTester tester;
  @Autowired private JdbcTemplate jdbc;

  private GreenMail greenMail;
  private String statusBefore;

  @BeforeEach
  void setUp() throws IOException {
    greenMail = new GreenMail(new ServerSetup(freePort(), "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
    greenMail.setUser("opaa@intern.example", SMTP_USER, SMTP_PASSWORD);
    greenMail.start();
    statusBefore = storedSendStatus();
  }

  @AfterEach
  void tearDown() {
    greenMail.stop();
  }

  @Test
  void aCheckSignsInAndSendsNothing() {
    MailConnectionTester.Outcome outcome = tester.test(settings(SMTP_USER, SMTP_PASSWORD), null);

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.message())
        .isEqualTo(
            "Verbindung und Anmeldung beim Mailserver sind gelungen."
                + " Es wurde keine Testnachricht gesendet.");
    assertThat(greenMail.getReceivedMessages()).isEmpty();
    assertThat(storedSendStatus()).isEqualTo(statusBefore);
  }

  @Test
  void aWrongPasswordIsARefusedSignInWithoutThePassword() {
    MailConnectionTester.Outcome outcome =
        tester.test(settings(SMTP_USER, "falsches-Passwort"), null);

    assertThat(outcome.succeeded()).isFalse();
    assertThat(outcome.message())
        .isEqualTo(MailConnectionTester.Failure.SIGN_IN_REFUSED.message)
        .doesNotContain("falsches-Passwort");
    assertThat(storedSendStatus()).isEqualTo(statusBefore);
  }

  // regression guard: a service that is no mail server shows only the fixed sentence
  @Test
  void aServiceThatIsNoMailServerAnswersWithTheFixedSentence() throws Exception {
    try (ServerSocket other = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      Thread greeter =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (java.net.Socket client = other.accept()) {
                      client
                          .getOutputStream()
                          .write("-ERR wrong number of arguments\r\n".getBytes());
                      client.getOutputStream().flush();
                    } catch (IOException ignored) {
                      // the check closes the connection itself
                    }
                  });
      MailSettingsSnapshot notSmtp =
          new MailSettingsSnapshot(
              true,
              "127.0.0.1",
              other.getLocalPort(),
              null,
              null,
              MailEncryption.NONE,
              "opaa@intern.example",
              null);

      MailConnectionTester.Outcome outcome = tester.test(notSmtp, null);

      assertThat(outcome.succeeded()).isFalse();
      assertThat(outcome.message())
          .isEqualTo(MailConnectionTester.Failure.NOT_A_MAIL_SERVER.message)
          .doesNotContain("ERR");
      greeter.join(5_000);
    }
  }

  @Test
  void theTestMessageGoesOnlyToTheGivenAddress() throws Exception {
    MailConnectionTester.Outcome outcome =
        tester.test(settings(SMTP_USER, SMTP_PASSWORD), " " + RECIPIENT + " ");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.message())
        .isEqualTo(
            "Verbindung und Anmeldung beim Mailserver sind gelungen."
                + " Die Testnachricht wurde dem Mailserver übergeben.")
        .doesNotContain(RECIPIENT);
    assertThat(greenMail.waitForIncomingEmail(10_000, 1)).isTrue();
    MimeMessage[] received = greenMail.getReceivedMessages();
    assertThat(received).hasSize(1);
    assertThat(received[0].getAllRecipients()[0].toString()).isEqualTo(RECIPIENT);
    assertThat(received[0].getFrom()[0].toString()).contains("opaa@intern.example");
    assertThat(storedSendStatus()).isEqualTo(statusBefore);
  }

  @Test
  void aServerThatDoesNotAnswerFailsTheCheck() throws IOException {
    MailSettingsSnapshot closedPort =
        new MailSettingsSnapshot(
            true,
            "127.0.0.1",
            freePort(),
            null,
            null,
            MailEncryption.NONE,
            "opaa@intern.example",
            null);

    MailConnectionTester.Outcome outcome = tester.test(closedPort, RECIPIENT);

    assertThat(outcome.succeeded()).isFalse();
    assertThat(outcome.message()).isEqualTo(MailConnectionTester.Failure.UNREACHABLE.message);
    assertThat(greenMail.getReceivedMessages()).isEmpty();
  }

  private MailSettingsSnapshot settings(String user, String password) {
    return new MailSettingsSnapshot(
        true,
        "127.0.0.1",
        greenMail.getSmtp().getPort(),
        user,
        password,
        MailEncryption.NONE,
        "opaa@intern.example",
        "OPAA");
  }

  private String storedSendStatus() {
    return jdbc.queryForObject(
        "SELECT coalesce(last_success_at::text, '-') || '/' || coalesce(last_failure_at::text, '-')"
            + " FROM mail_settings WHERE id = 1",
        String.class);
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
