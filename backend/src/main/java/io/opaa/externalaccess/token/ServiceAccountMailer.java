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
 * The two reminders of a service account (ADR-0035, Entscheidung 7): before one of its tokens and
 * before a library's consent to it runs out. They go to the responsible person and to the system
 * administration, over the mail machinery of ADR-0033 - SMTP off turns them off, and nothing else.
 */
@DataFlow("mail")
@Component
public class ServiceAccountMailer {

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY);

  private final MailService mail;
  private final Clock clock;

  public ServiceAccountMailer(MailService mail, Clock clock) {
    this.mail = mail;
    this.clock = clock;
  }

  /** Queued to the mail outbox, in the caller's transaction when there is one. */
  public void sendTokenExpiring(
      User recipient, String serviceAccountName, String tokenName, Instant expiresAt) {
    mail.enqueue(
        MailTemplateKey.SERVICE_ACCOUNT_TOKEN_EXPIRING,
        Locale.GERMAN,
        recipient.getEmail(),
        Map.of(
            "displayName",
            recipient.getDisplayName() == null ? "" : recipient.getDisplayName(),
            "serviceAccountName",
            serviceAccountName,
            "tokenName",
            tokenName,
            "expiresAtHuman",
            "am " + DATE.format(expiresAt.atZone(clock.getZone()))));
  }

  /** Queued to the mail outbox, in the caller's transaction when there is one. */
  public void sendConsentExpiring(
      User recipient, String serviceAccountName, String libraryName, Instant expiresAt) {
    mail.enqueue(
        MailTemplateKey.SERVICE_ACCOUNT_CONSENT_EXPIRING,
        Locale.GERMAN,
        recipient.getEmail(),
        Map.of(
            "displayName",
            recipient.getDisplayName() == null ? "" : recipient.getDisplayName(),
            "serviceAccountName",
            serviceAccountName,
            "libraryName",
            libraryName,
            "expiresAtDate",
            "am " + DATE.format(expiresAt.atZone(clock.getZone()))));
  }
}
