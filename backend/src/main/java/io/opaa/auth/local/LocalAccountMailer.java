package io.opaa.auth.local;

import io.opaa.api.types.MailDeliveryPath;
import io.opaa.auth.User;
import io.opaa.auth.local.LocalActionTokenService.IssuedActionToken;
import io.opaa.common.PublicBaseUrl;
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
 * Every mail the local account management sends to a person or an administrator (ADR-0033,
 * Entscheidung 11: kein stiller Eingriff). Not transactional and never called inside a transaction
 * - {@link MailService#send} is synchronous with SMTP timeouts, so a send follows the commit of the
 * act it announces. A link mail answers with its {@link LinkDelivery}: sent, failed with the link
 * for hand-over, or - SMTP off or no public base URL - the link displayed without an attempt.
 */
@Component
public class LocalAccountMailer {

  static final String LOCK_REASON_ADMIN = "Sperre durch die Systemverwaltung";
  static final String LOCK_REASON_INACTIVITY = "Keine Aktivität seit %d Tagen";

  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm 'Uhr'", Locale.GERMANY);
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY);

  private final MailService mail;
  private final PublicBaseUrl publicBaseUrl;
  private final ZoneId zone;

  @Autowired
  public LocalAccountMailer(MailService mail, PublicBaseUrl publicBaseUrl) {
    this(mail, publicBaseUrl, ZoneId.systemDefault());
  }

  LocalAccountMailer(MailService mail, PublicBaseUrl publicBaseUrl, ZoneId zone) {
    this.mail = mail;
    this.publicBaseUrl = publicBaseUrl;
    this.zone = zone;
  }

  public LinkDelivery sendInvitation(User user, IssuedActionToken token) {
    return deliverLink(MailTemplateKey.LOCAL_ACCOUNT_INVITATION, user, token);
  }

  public LinkDelivery sendAdminPasswordReset(User user, IssuedActionToken token) {
    return deliverLink(MailTemplateKey.ADMIN_PASSWORD_RESET, user, token);
  }

  /**
   * The self-service reset link (#1538). No link fallback: the flow exists only with a public base
   * URL, and its caller must never learn whether a mail left (ADR-0033, Entscheidung 11).
   */
  public SendResult sendPasswordReset(User user, IssuedActionToken token) {
    return send(
        MailTemplateKey.PASSWORD_RESET,
        user,
        Map.of(
            "actionUrl",
            LocalAccountLinks.setPasswordLink(publicBaseUrl, token.rawToken()),
            "expiresAtHuman",
            until(token.expiresAt())));
  }

  /** The verification link of a self-registration (#1538); no link fallback, as above. */
  public SendResult sendRegistrationVerification(User user, IssuedActionToken token) {
    return send(
        MailTemplateKey.REGISTRATION_VERIFICATION,
        user,
        Map.of(
            "actionUrl",
            LocalAccountLinks.verifyEmailLink(publicBaseUrl, token.rawToken()),
            "expiresAtHuman",
            until(token.expiresAt())));
  }

  /**
   * {@code reason} is the sentence for the person; {@code null} means the administrative default.
   */
  public void sendLocked(User user, String reason) {
    send(
        MailTemplateKey.ACCOUNT_LOCKED,
        user,
        Map.of("reason", reason == null || reason.isBlank() ? LOCK_REASON_ADMIN : reason.trim()));
  }

  public void sendLockedForInactivity(User user, int inactiveDays) {
    send(
        MailTemplateKey.ACCOUNT_LOCKED,
        user,
        Map.of("reason", LOCK_REASON_INACTIVITY.formatted(inactiveDays)));
  }

  public void sendUnlocked(User user) {
    send(
        MailTemplateKey.ACCOUNT_UNLOCKED,
        user,
        Map.of(
            "actionUrl",
            LocalAccountLinks.absoluteOrEmpty(publicBaseUrl, LocalAccountLinks.LOGIN_PATH)));
  }

  public void sendExpiring(User user, Instant expiresAt) {
    send(MailTemplateKey.ACCOUNT_EXPIRING, user, Map.of("expiresAtHuman", on(expiresAt)));
  }

  /**
   * The review reminder to one system administrator: a count and the link to the list, no names.
   */
  public void sendReviewReminder(User admin, long count) {
    send(
        MailTemplateKey.ADMIN_REVIEW_REMINDER,
        admin,
        Map.of(
            "count",
            String.valueOf(count),
            "actionUrl",
            LocalAccountLinks.absoluteOrEmpty(
                publicBaseUrl, LocalAccountLinks.ADMIN_LOCAL_USERS_PATH)));
  }

  private LinkDelivery deliverLink(MailTemplateKey key, User user, IssuedActionToken token) {
    String link = LocalAccountLinks.setPasswordLink(publicBaseUrl, token.rawToken());
    if (!publicBaseUrl.isConfigured()) {
      return new LinkDelivery(MailDeliveryPath.LINK_DISPLAYED, link);
    }
    SendResult result =
        send(key, user, Map.of("actionUrl", link, "expiresAtHuman", until(token.expiresAt())));
    return switch (result) {
      case SendResult.Sent ignored -> new LinkDelivery(MailDeliveryPath.MAIL_SENT, null);
      case SendResult.Skipped ignored -> new LinkDelivery(MailDeliveryPath.LINK_DISPLAYED, link);
      case SendResult.Failed ignored -> new LinkDelivery(MailDeliveryPath.MAIL_FAILED, link);
    };
  }

  private SendResult send(MailTemplateKey key, User user, Map<String, Object> variables) {
    Map<String, Object> all = new java.util.HashMap<>(variables);
    all.put("displayName", user.getDisplayName() == null ? "" : user.getDisplayName());
    return mail.send(key, Locale.GERMAN, user.getEmail(), all);
  }

  /** "bis 12.09.2026, 14:00 Uhr" - fills "Der Link ist {{expiresAtHuman}} gültig". */
  String until(Instant instant) {
    return "bis " + DATE_TIME.format(instant.atZone(zone));
  }

  /** "am 25.09.2026" - fills "läuft {{expiresAtHuman}} ab". */
  String on(Instant instant) {
    return "am " + DATE.format(instant.atZone(zone));
  }
}
