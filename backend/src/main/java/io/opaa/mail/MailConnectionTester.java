package io.opaa.mail;

import io.opaa.compliance.DataFlow;
import java.util.Locale;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Checks SMTP settings that are not stored yet (ADR-0033 Entscheidung 10): a connection to the
 * server, signed in when a user name is set, without sending anything, and on request the test
 * message to a given address through the same transport. Every wait is bounded by the {@link
 * SmtpProperties} timeouts; nothing is written to the stored settings or their send status. The
 * outcome is German and user-facing and names no address; a failed check is a result, never an
 * exception.
 */
@DataFlow("mail")
@Component
public class MailConnectionTester {

  /** The outcome of one check. */
  public record Outcome(boolean succeeded, String message) {}

  static final String CONNECTED = "Die Verbindung zum Mailserver ist gelungen.";
  static final String SIGNED_IN = "Verbindung und Anmeldung beim Mailserver sind gelungen.";
  static final String NOTHING_SENT = "Es wurde keine Nachricht versendet.";
  static final String TEST_SENT = "Die Testnachricht wurde dem Mailserver übergeben.";

  private final MailSenderProvider senders;
  private final MailService mailService;
  private final MailTestService testMails;

  public MailConnectionTester(
      MailSenderProvider senders, MailService mailService, MailTestService testMails) {
    this.senders = senders;
    this.mailService = mailService;
    this.testMails = testMails;
  }

  /**
   * Connects with {@code settings}; with a non-blank {@code testRecipient} then sends {@link
   * MailTemplateKey#TEST_MAIL} there.
   */
  public Outcome test(MailSettingsSnapshot settings, String testRecipient) {
    MailSenderProvider.Transport transport;
    try {
      transport = senders.transportFor(settings);
      if (!(transport.sender() instanceof JavaMailSenderImpl sender)) {
        return new Outcome(false, "Der Mailversand lässt sich nicht prüfen.");
      }
      sender.testConnection();
    } catch (Exception e) {
      return new Outcome(
          false,
          "Die Verbindung zum Mailserver ist fehlgeschlagen: " + MailService.maskedCauseOf(e));
    }
    String connected = settings.authenticated() ? SIGNED_IN : CONNECTED;
    if (!StringUtils.hasText(testRecipient)) {
      return new Outcome(true, connected + " " + NOTHING_SENT);
    }
    SendResult result =
        mailService.sendThrough(
            transport,
            MailTemplateKey.TEST_MAIL,
            Locale.GERMAN,
            testRecipient.strip(),
            testMails.variables(MailTemplateKey.TEST_MAIL, null));
    return result.isSent()
        ? new Outcome(true, connected + " " + TEST_SENT)
        : new Outcome(
            false, connected + " Die Testnachricht ging nicht hinaus: " + result.reasonOrNull());
  }
}
