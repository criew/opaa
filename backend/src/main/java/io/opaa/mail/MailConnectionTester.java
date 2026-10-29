package io.opaa.mail;

import io.opaa.compliance.DataFlow;
import jakarta.mail.AuthenticationFailedException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Checks SMTP settings that are not stored yet (ADR-0033 Entscheidung 10), for the installation: a
 * connection to the server, signed in when a user name is set, without sending anything, and on
 * request the test message to a given address through the same transport. Every wait is bounded by
 * the {@link SmtpProperties} timeouts; nothing is written to the stored settings or their send
 * status. A success says whether a test message went out, never to which address, as the
 * installation stores the sentence. A failure answers with one of a few fixed German sentences
 * ({@link Failure}), so the answer tells a probe nothing about the far end; the cause goes to the
 * log only, without address and without the password. A failed check is a result, never an
 * exception.
 */
@DataFlow("mail")
@Component
public class MailConnectionTester {

  /** The outcome of one check. */
  public record Outcome(boolean succeeded, String message) {}

  /** What the answer says about a failed connection; the cause itself stays in the log. */
  enum Failure {
    UNREACHABLE("Der Mailserver ist nicht erreichbar. Bitte Server und Port prüfen."),
    TLS(
        "Die verschlüsselte Verbindung zum Mailserver ist fehlgeschlagen. Bitte die"
            + " Verschlüsselung prüfen."),
    SIGN_IN_REFUSED(
        "Der Mailserver hat die Anmeldung abgelehnt. Bitte Benutzername und Passwort prüfen."),
    NOT_A_MAIL_SERVER(
        "Der Server antwortet nicht wie ein Mailserver. Bitte Server, Port und Verschlüsselung"
            + " prüfen.");

    final String message;

    Failure(String message) {
      this.message = message;
    }
  }

  static final String CONNECTED = "Die Verbindung zum Mailserver ist gelungen.";
  static final String SIGNED_IN = "Verbindung und Anmeldung beim Mailserver sind gelungen.";
  static final String NOTHING_SENT = "Es wurde keine Testnachricht gesendet.";
  static final String TEST_SENT = "Die Testnachricht wurde dem Mailserver übergeben.";
  static final String TEST_REFUSED = "Der Mailserver hat die Testnachricht nicht angenommen.";

  private static final Logger log = LoggerFactory.getLogger(MailConnectionTester.class);

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
        return new Outcome(false, Failure.NOT_A_MAIL_SERVER.message);
      }
      sender.testConnection();
    } catch (Exception e) {
      Failure failure = classify(e);
      log.warn(
          "Installation mail check failed ({}): {}",
          failure,
          MailLogText.withoutLineBreaks(MailService.maskedCauseOf(e, settings.password())));
      return new Outcome(false, failure.message);
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
    if (result.isSent()) {
      return new Outcome(true, connected + " " + TEST_SENT);
    }
    log.warn(
        "Installation test message refused: {}",
        redacted(result.reasonOrNull(), settings.password()));
    return new Outcome(false, connected + " " + TEST_REFUSED);
  }

  /** The class of a failed connection, from the innermost cause that names one. */
  static Failure classify(Throwable error) {
    for (Throwable cause = error; cause != null; cause = next(cause)) {
      if (cause instanceof AuthenticationFailedException) {
        return Failure.SIGN_IN_REFUSED;
      }
      if (cause instanceof SSLException || mentionsStartTls(cause)) {
        return Failure.TLS;
      }
      if (cause instanceof ConnectException
          || cause instanceof UnknownHostException
          || cause instanceof NoRouteToHostException
          || cause instanceof PortUnreachableException
          || isConnectTimeout(cause)) {
        return Failure.UNREACHABLE;
      }
    }
    return Failure.NOT_A_MAIL_SERVER;
  }

  private static Throwable next(Throwable cause) {
    return cause.getCause() == cause ? null : cause.getCause();
  }

  private static boolean mentionsStartTls(Throwable cause) {
    String message = cause.getMessage();
    return message != null && message.toUpperCase(Locale.ROOT).contains("STARTTLS");
  }

  private static boolean isConnectTimeout(Throwable cause) {
    String message = cause.getMessage();
    return cause instanceof SocketTimeoutException
        && message != null
        && message.toLowerCase(Locale.ROOT).contains("connect");
  }

  /** {@code text} without {@code secret} and without line-breaking characters, for a log line. */
  static String redacted(String text, String secret) {
    return MailLogText.withoutLineBreaks(MailLogText.withoutSecret(text, secret));
  }
}
