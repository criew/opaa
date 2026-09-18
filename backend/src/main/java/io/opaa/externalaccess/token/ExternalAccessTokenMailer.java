package io.opaa.externalaccess.token;

import io.opaa.auth.User;
import io.opaa.mail.MailService;
import io.opaa.mail.MailTemplateKey;
import io.opaa.mail.SendResult;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The one mail of the external-access channel: the reminder before a token expires. Goes over the
 * mail machinery of ADR-0033 ({@code mail_settings}, {@code mail_templates}), so switching SMTP off
 * turns the reminder off - and nothing else: the expiry happens either way.
 *
 * <p>The token's name is in this mail on purpose. It is the only thing by which the person still
 * recognises which tool will stop working - and unlike the audit trail, a mail to the person
 * themselves is not a Nachweisbestand.
 */
@Component
public class ExternalAccessTokenMailer {

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY);

  private final MailService mail;
  private final ZoneId zone;

  @Autowired
  public ExternalAccessTokenMailer(MailService mail) {
    this(mail, ZoneId.systemDefault());
  }

  ExternalAccessTokenMailer(MailService mail, ZoneId zone) {
    this.mail = mail;
    this.zone = zone;
  }

  public SendResult sendExpiring(User owner, String tokenName, Instant expiresAt) {
    return mail.send(
        MailTemplateKey.EXTERNAL_ACCESS_TOKEN_EXPIRING,
        Locale.GERMAN,
        owner.getEmail(),
        Map.of(
            "displayName",
            owner.getDisplayName() == null ? "" : owner.getDisplayName(),
            "tokenName",
            tokenName,
            "expiresAtHuman",
            "am " + DATE.format(expiresAt.atZone(zone))));
  }
}
