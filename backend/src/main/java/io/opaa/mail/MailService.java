package io.opaa.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The one way OPAA sends mail (#1536, ADR-0033 Entscheidung 10).
 *
 * <p><b>{@link #send} never throws.</b> It returns a {@link SendResult}: {@code Skipped} while SMTP
 * is not configured, {@code Failed} with the cause when an attempt fails, {@code Sent} otherwise.
 * The auth flows this exists for are tied to a user action whose result the caller needs at once,
 * so sending is <b>synchronous and bounded by the {@link SmtpProperties} timeouts</b> - no outbox,
 * no retry. A later digest can add a queue without changing this contract.
 *
 * <p><b>A failure is never invisible.</b> Every {@code Failed} writes a log line with the cause and
 * the template key, updates {@code last_failure_at}/{@code last_failure_reason} - which the
 * settings page and {@link MailHealthIndicator} read - and carries the cause back to its caller.
 * The recipient appears in the log only shortened ({@link MailRecipients}); a mail server having a
 * bad day must not turn the application log into an address list.
 *
 * <p>Messages with an HTML part go out as {@code multipart/alternative}, so a client that renders
 * no HTML still shows the text version rather than markup.
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
   * recipient}.
   *
   * @param variables the values for the placeholders the template declares; {@code productName} is
   *     supplied from the branding and need not be passed
   */
  public SendResult send(
      MailTemplateKey key, Locale locale, String recipient, Map<String, Object> variables) {
    if (!StringUtils.hasText(recipient)) {
      return failed(key, recipient, "Keine Empfängeradresse vorhanden");
    }
    if (!senderProvider.isEnabled()) {
      return new SendResult.Skipped(NOT_CONFIGURED);
    }

    RenderedMail mail;
    try {
      mail = templates.render(key, MailTemplateService.localeTag(locale), variables);
    } catch (RuntimeException e) {
      return failed(key, recipient, "Die Vorlage konnte nicht gefüllt werden: " + e.getMessage());
    }

    JavaMailSender sender;
    MailSettingsSnapshot snapshot;
    try {
      sender = senderProvider.current();
      snapshot = senderProvider.settings();
    } catch (RuntimeException e) {
      return failed(key, recipient, e.getMessage());
    }
    if (sender == null) {
      return new SendResult.Skipped(NOT_CONFIGURED);
    }

    try {
      sender.send(compose(sender, snapshot, recipient, mail));
    } catch (Exception e) {
      return failed(key, recipient, causeOf(e));
    }
    settingsService.recordSendOutcome(Instant.now(), null);
    return new SendResult.Sent(recipient);
  }

  /**
   * Composes the message as a flat {@code multipart/alternative} rather than through {@link
   * MimeMessageHelper}'s multipart mode: that mode nests alternative inside related inside mixed,
   * which is what an attachment or an inline image needs and this subsystem has neither of. A
   * client picking between two alternatives should not have to walk three levels to find them.
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

  private SendResult failed(MailTemplateKey key, String recipient, String reason) {
    log.warn(
        "Mailversand fehlgeschlagen (Vorlage {}, Empfaenger {}): {}",
        key.key(),
        MailRecipients.shortened(recipient),
        reason);
    settingsService.recordSendOutcome(Instant.now(), reason);
    return new SendResult.Failed(reason);
  }

  /**
   * The message of the innermost cause. Jakarta Mail wraps the useful part ("Connection refused",
   * "535 authentication failed") inside a {@code MailSendException} whose own message is a list of
   * failed recipients - which is exactly the part that must not be logged.
   */
  private static String causeOf(Exception e) {
    Throwable cause = e;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    String message = cause.getMessage();
    String type = cause.getClass().getSimpleName();
    return StringUtils.hasText(message) ? type + ": " + message : type;
  }
}
