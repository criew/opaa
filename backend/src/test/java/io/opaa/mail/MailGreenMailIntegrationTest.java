package io.opaa.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import io.opaa.api.types.MailEncryption;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.branding.BrandingSettingsService;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #1536, ADR-0033 Entscheidung 10: the whole send path against a real SMTP server (GreenMail,
 * in-JVM) rather than a mocked {@code JavaMailSender} - the SMTP conversation, the authentication,
 * the MIME structure and the branding in the HTML part.
 *
 * <p>This test carries the acceptance criterion the issue phrased as a manual Mailpit screenshot:
 * <b>without settings {@code send} skips and does not throw, and after a {@code PUT} the same
 * process sends without a restart</b> - which is exactly what a mocked transport cannot show,
 * because the snapshot rebuild after commit is the thing under test.
 *
 * <p>GreenMail runs on a port claimed from the operating system rather than its documented default,
 * so a parallel build on the same machine cannot collide with it. Plain SMTP: what the STARTTLS and
 * SSL modes put into the transport is proved by {@code MailSenderProviderTest}, and a TLS handshake
 * against a self-signed in-JVM server would prove the test's own certificate handling, not OPAA's.
 */
@OpaaIntegrationTest
class MailGreenMailIntegrationTest {

  private static final String RECIPIENT = "erika.mustermann@amt.example";
  private static final String SMTP_USER = "kennung";
  private static final String SMTP_PASSWORD = "geheimesKennwort";

  @Autowired private MailService mailService;
  @Autowired private MailSettingsService mailSettingsService;
  @Autowired private BrandingSettingsService brandingSettingsService;
  @Autowired private MailTemplateRepository templateRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private GreenMail greenMail;
  private UUID organizationId;
  private UUID userId;

  @BeforeEach
  void setUp() throws IOException {
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "GreenMail Org")).getId();
    User user = new User(UUID.randomUUID().toString(), "test-issuer", "gm@example.com", "Test");
    user.setOrganizationId(organizationId);
    userId = userRepository.save(user).getId();

    greenMail = new GreenMail(new ServerSetup(freePort(), "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
    greenMail.setUser("opaa@intern.example", SMTP_USER, SMTP_PASSWORD);
    greenMail.start();
  }

  @AfterEach
  void tearDown() {
    if (greenMail != null) {
      greenMail.stop();
    }
    mailSettingsService.updateSettings(
        organizationId,
        userId,
        new MailSettingsUpdate(false, null, null, null, "", MailEncryption.STARTTLS, null, null));
    jdbcTemplate.update(
        "UPDATE mail_settings SET last_success_at = NULL, last_failure_at = NULL,"
            + " last_failure_reason = NULL WHERE id = 1");
    templateRepository.deleteAll();
    brandingSettingsService.updateBranding(organizationId, userId, null, null, null, null);
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    userRepository.deleteById(userId);
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void skipsWithoutSettingsAndSendsAfterThePutInTheSameProcess() throws Exception {
    SendResult beforeConfiguration =
        mailService.send(MailTemplateKey.TEST_MAIL, Locale.GERMAN, RECIPIENT, testMailVariables());
    assertThat(beforeConfiguration).isInstanceOf(SendResult.Skipped.class);
    assertThat(greenMail.getReceivedMessages()).isEmpty();

    configureSmtp();

    SendResult afterConfiguration =
        mailService.send(MailTemplateKey.TEST_MAIL, Locale.GERMAN, RECIPIENT, testMailVariables());

    assertThat(afterConfiguration).isEqualTo(new SendResult.Sent(RECIPIENT));
    assertThat(greenMail.waitForIncomingEmail(10_000, 1)).isTrue();
    MimeMessage received = greenMail.getReceivedMessages()[0];
    assertThat(received.getAllRecipients()[0].toString()).isEqualTo(RECIPIENT);
    assertThat(received.getFrom()[0].toString()).contains("opaa@intern.example").contains("OPAA");
    assertThat(mailSettingsService.status().lastSuccessAt()).isNotNull();
    assertThat(mailSettingsService.status().lastAttemptFailed()).isFalse();
  }

  @Test
  void sendsBothPartsAndCarriesTheOperatorsBrandingInTheHtmlOne() throws Exception {
    brandingSettingsService.updateBranding(
        organizationId, userId, "Landesamt-Assistent", null, "#7A1FA2", null);
    configureSmtp();

    SendResult result =
        mailService.send(
            MailTemplateKey.LOCAL_ACCOUNT_INVITATION,
            Locale.GERMAN,
            RECIPIENT,
            Map.of(
                "displayName",
                "Erika Mustermann",
                "actionUrl",
                "https://opaa.amt.example/konto/passwort?token=T",
                "expiresAtHuman",
                "noch 24 Stunden"));

    assertThat(result.isSent()).isTrue();
    assertThat(greenMail.waitForIncomingEmail(10_000, 1)).isTrue();
    MimeMessage received = greenMail.getReceivedMessages()[0];

    assertThat(received.getSubject()).isEqualTo("Ihr Zugang zu Landesamt-Assistent");
    assertThat(received.getContentType()).startsWith("multipart/alternative");

    assertThat(partOfType(received, "text/plain"))
        .contains("Erika Mustermann")
        .contains("https://opaa.amt.example/konto/passwort?token=T");
    assertThat(partOfType(received, "text/html"))
        .contains("Landesamt-Assistent")
        .contains("#7A1FA2")
        .contains("Passwort festlegen")
        .contains("https://opaa.amt.example/konto/passwort?token=T");
  }

  @Test
  void reportsAFailedSendWithItsCauseAndRecordsItInsteadOfThrowing() {
    configureSmtp();
    greenMail.stop();

    SendResult result =
        mailService.send(MailTemplateKey.TEST_MAIL, Locale.GERMAN, RECIPIENT, testMailVariables());

    assertThat(result).isInstanceOf(SendResult.Failed.class);
    assertThat(result.reasonOrNull()).isNotBlank();
    assertThat(mailSettingsService.status().lastAttemptFailed()).isTrue();
    assertThat(mailSettingsService.currentSettings().getLastFailureReason()).isNotBlank();
  }

  private void configureSmtp() {
    mailSettingsService.updateSettings(
        organizationId,
        userId,
        new MailSettingsUpdate(
            true,
            "127.0.0.1",
            greenMail.getSmtp().getPort(),
            SMTP_USER,
            SMTP_PASSWORD,
            MailEncryption.NONE,
            "opaa@intern.example",
            "OPAA"));
  }

  private static Map<String, Object> testMailVariables() {
    return Map.of("displayName", "Erika Mustermann", "occurredAtHuman", "am 11.09.2026");
  }

  /** The decoded content of the first part of {@code message} whose type matches. */
  private static String partOfType(MimeMessage message, String mimeType) throws Exception {
    MimeMultipart multipart = (MimeMultipart) message.getContent();
    for (int i = 0; i < multipart.getCount(); i++) {
      Part part = multipart.getBodyPart(i);
      if (part.isMimeType(mimeType)) {
        return part.getContent().toString();
      }
      if (part.getContent() instanceof MimeMultipart nested) {
        for (int j = 0; j < nested.getCount(); j++) {
          Part nestedPart = nested.getBodyPart(j);
          if (nestedPart.isMimeType(mimeType)) {
            return nestedPart.getContent().toString();
          }
        }
      }
    }
    throw new AssertionError("Kein Teil vom Typ " + mimeType + " in der Nachricht");
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
