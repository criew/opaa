package io.opaa.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The one way OPAA sends mail (#1536, ADR-0033 Entscheidung 10).
 *
 * <p><b>{@link #send} never throws</b> - not for a missing encryption key, not for an unreadable
 * settings row, not for a mail server that refuses the connection. It returns a {@link SendResult},
 * and the caller decides what to tell the person waiting for it.
 *
 * <p><b>No address reaches a log line, {@code last_failure_reason} or an API response:</b> every
 * cause passes {@link MailRecipients#maskAddresses} first.
 *
 * <p>Sending is synchronous and bounded by the {@link SmtpProperties} timeouts - no outbox, no
 * retry; a message with an HTML part goes out as {@code multipart/alternative}.
 */
@Service
public class MailService {

  private static final Logger log = LoggerFactory.getLogger(MailService.class);

  private static final String ENCODING = "UTF-8";
  private static final String NOT_CONFIGURED =
      "Der E-Mail-Versand ist nicht eingerichtet (SMTP ausgeschaltet oder ohne Server)";

  private final MailSenderProvider senderProvider;
  private final MailTemplateService templates;
  private final MailSettingsService settingsService;

  public MailService(
      MailSenderProvider senderProvider,
      MailTemplateService templates,
      MailSettingsService settingsService) {
    this.senderProvider = senderProvider;
    this.templates = templates;
    this.settingsService = settingsService;
  }

  /**
   * Renders {@code key} for {@code locale} with {@code variables} and sends it to {@code
   * recipient}. Returns the outcome; never throws.
   *
   * @param variables the values for the placeholders the template declares; {@code productName} is
   *     supplied from the branding and need not be passed
   */
  public SendResult send(
      MailTemplateKey key, Locale locale, String recipient, Map<String, Object> variables) {
    try {
      return attemptSend(key, locale, recipient, variables);
    } catch (RuntimeException e) {
      // Guards the whole body, not only the transport: a missing OPAA_SETTINGS_ENCRYPTION_KEY makes
      // the settings snapshot itself raise, and an account creation must not fail for that.
      return abort(key, recipient, causeOf(e));
    }
  }

  private SendResult attemptSend(
      MailTemplateKey key, Locale locale, String recipient, Map<String, Object> variables) {
    if (!StringUtils.hasText(recipient)) {
      return abort(key, recipient, "Keine Empfängeradresse vorhanden");
    }
    if (!senderProvider.isEnabled()) {
      return new SendResult.Skipped(NOT_CONFIGURED);
    }

    RenderedMail mail;
    try {
      mail = templates.render(key, MailTemplateService.localeTag(locale), variables);
    } catch (RuntimeException e) {
      return abort(key, recipient, "Die Vorlage konnte nicht gefüllt werden: " + e.getMessage());
    }

    JavaMailSender sender = senderProvider.current();
    if (sender == null) {
      return new SendResult.Skipped(NOT_CONFIGURED);
    }

    try {
      sender.send(compose(sender, senderProvider.settings(), recipient, mail));
    } catch (Exception e) {
      return transportFailed(key, recipient, causeOf(e));
    }
    recordOutcome(null);
    return new SendResult.Sent(recipient);
  }

  /**
   * Composes a flat {@code multipart/alternative} rather than using {@link MimeMessageHelper}'s
   * multipart mode, which nests alternative inside related inside mixed - what an attachment needs
   * and this subsystem has none.
   */
  private MimeMessage compose(
      JavaMailSender sender, MailSettingsSnapshot snapshot, String recipient, RenderedMail mail)
      throws MessagingException, UnsupportedEncodingException {
    MimeMessage message = sender.createMimeMessage();
    MimeMessageHelper helper = new MimeMessageHelper(message, false, ENCODING);
    if (StringUtils.hasText(snapshot.fromAddress())) {
      if (StringUtils.hasText(snapshot.fromName())) {
        helper.setFrom(snapshot.fromAddress(), snapshot.fromName());
      } else {
        helper.setFrom(snapshot.fromAddress());
      }
    }
    helper.setTo(recipient);
    helper.setSubject(mail.subject());
    if (mail.hasHtml()) {
      message.setContent(alternativeOf(mail));
    } else {
      helper.setText(mail.bodyPlain(), false);
    }
    return message;
  }

  /** The text part first, the HTML part second - the order that tells a client which to prefer. */
  private static MimeMultipart alternativeOf(RenderedMail mail) throws MessagingException {
    MimeBodyPart text = new MimeBodyPart();
    text.setText(mail.bodyPlain(), ENCODING);
    MimeBodyPart html = new MimeBodyPart();
    html.setContent(mail.bodyHtml(), "text/html; charset=" + ENCODING);

    MimeMultipart alternative = new MimeMultipart("alternative");
    alternative.addBodyPart(text);
    alternative.addBodyPart(html);
    return alternative;
  }

  /**
   * A failure before the transport was reached - no recipient, an unrenderable template, an
   * unreadable configuration. Logged, but deliberately <b>not</b> written to {@code
   * last_failure_at}/{@code last_failure_reason}: those two answer whether the mail server works,
   * and a template typo recorded there sends an operator looking at the wrong thing.
   */
  private SendResult abort(MailTemplateKey key, String recipient, String reason) {
    String masked = MailRecipients.maskAddresses(reason);
    log.warn(
        "Mailversand abgebrochen (Vorlage {}, Empfaenger {}): {}",
        key.key(),
        MailRecipients.shortened(recipient),
        masked);
    return new SendResult.Failed(masked);
  }

  /** The mail server was reached and refused: logged and written to the settings row. */
  private SendResult transportFailed(MailTemplateKey key, String recipient, String reason) {
    String masked = MailRecipients.maskAddresses(reason);
    log.warn(
        "Mailversand fehlgeschlagen (Vorlage {}, Empfaenger {}): {}",
        key.key(),
        MailRecipients.shortened(recipient),
        masked);
    recordOutcome(masked);
    return new SendResult.Failed(masked);
  }

  /** A status write must never turn into the failure of the send it is describing. */
  private void recordOutcome(String failureReason) {
    try {
      settingsService.recordSendOutcome(Instant.now(), failureReason);
    } catch (RuntimeException e) {
      log.warn("Versandstatus konnte nicht fortgeschrieben werden: {}", e.getMessage());
    }
  }

  /**
   * The cause without an address in it. A {@link MailSendException} is unwrapped through its {@code
   * failedMessages} map and never through its own message: Spring builds that message from the
   * rejected recipients and, on the common path, constructs the exception from the map with no
   * cause at all - so walking {@code getCause()} would fall back to exactly that list.
   */
  private static String causeOf(Exception e) {
    if (e instanceof MailSendException sendException
        && !sendException.getFailedMessages().isEmpty()) {
      Set<String> causes = new LinkedHashSet<>();
      for (Exception failure : sendException.getFailedMessages().values()) {
        causes.add(innermostMessageOf(failure));
      }
      return String.join("; ", causes);
    }
    return innermostMessageOf(e);
  }

  private static String innermostMessageOf(Throwable throwable) {
    Throwable cause = throwable;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    String message = cause.getMessage();
    String type = cause.getClass().getSimpleName();
    return StringUtils.hasText(message) ? type + ": " + message : type;
  }
}
