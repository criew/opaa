package io.opaa.externalaccess.token;

import io.opaa.auth.User;
import io.opaa.compliance.DataFlow;
import io.opaa.mail.MailService;
import io.opaa.mail.MailTemplateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The two mails of the external-access channel to the owner of a token: the notice of its issuance
 * and the reminder before it expires. Both go over the mail machinery of ADR-0033 ({@code
 * mail_settings}, {@code mail_templates}), so switching SMTP off turns them off - and nothing else:
 * issuance and expiry happen either way.
 *
 * <p>The token's name is in these mails on purpose. It is the only thing by which the person
 * recognises the token - to revoke one they did not issue, or to know which tool will stop working
 * - and unlike the audit trail, a mail to the person themselves is not a Nachweisbestand.
 */
@DataFlow("mail")
@Component
public class ExternalAccessTokenMailer {

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY);

  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("dd.MM.yyyy 'um' HH:mm 'Uhr'", Locale.GERMANY);

  private final MailService mail;
  private final Clock clock;

  public ExternalAccessTokenMailer(MailService mail, Clock clock) {
    this.mail = mail;
    this.clock = clock;
  }

  /**
   * The notice that a token was issued for {@code owner} - the trace a taken-over session leaves
   * with the person. Queued in the issuing transaction, so a refused issuance sends nothing.
   */
  public void sendIssued(User owner, String tokenName, Instant issuedAt) {
    mail.enqueue(
        MailTemplateKey.EXTERNAL_ACCESS_TOKEN_ISSUED,
        Locale.GERMAN,
        owner.getEmail(),
        Map.of(
            "displayName",
            owner.getDisplayName() == null ? "" : owner.getDisplayName(),
            "tokenName",
            tokenName,
            "occurredAtHuman",
            "am " + DATE_TIME.format(issuedAt.atZone(clock.getZone()))));
  }

  /** Queued to the mail outbox, in the caller's transaction when there is one. */
  public void sendExpiring(User owner, String tokenName, Instant expiresAt) {
    mail.enqueue(
        MailTemplateKey.EXTERNAL_ACCESS_TOKEN_EXPIRING,
        Locale.GERMAN,
        owner.getEmail(),
        Map.of(
            "displayName",
            owner.getDisplayName() == null ? "" : owner.getDisplayName(),
            "tokenName",
            tokenName,
            "expiresAtHuman",
            "am " + DATE.format(expiresAt.atZone(clock.getZone()))));
  }
}
