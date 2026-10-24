package io.opaa.account;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.compliance.DataFlow;
import io.opaa.mail.MailService;
import io.opaa.mail.MailTemplateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tells every <em>other</em> system administrator of the organization that the bootstrap account
 * was used (ADR-0033, Entscheidung 5: mail {@code BOOTSTRAP_ACCOUNT_USED}), next to the audit event
 * {@link BootstrapAccountLoginListener} writes. The mails go to the outbox ({@link
 * MailService#enqueue}): the emergency sign-in waits for no mail server, and a failed delivery is
 * the mail subsystem's business. One WARN line with the account id - never the address - stays, so
 * an operator reading the log sees the emergency access was used.
 */
@DataFlow("mail")
@Component
public class MailingBootstrapLoginNotifier implements BootstrapLoginNotifier {

  private static final Logger log = LoggerFactory.getLogger(MailingBootstrapLoginNotifier.class);
  private static final DateTimeFormatter OCCURRED_AT =
      DateTimeFormatter.ofPattern("'am' dd.MM.yyyy 'um' HH:mm 'Uhr'", Locale.GERMANY);

  private final UserRepository users;
  private final MailService mail;
  private final Clock clock;

  public MailingBootstrapLoginNotifier(UserRepository users, MailService mail, Clock clock) {
    this.users = users;
    this.mail = mail;
    this.clock = clock;
  }

  @Override
  public void bootstrapAccountSignedIn(User bootstrapAccount, Instant at) {
    log.warn(
        "The bootstrap system administrator account {} signed in at {} (audited as"
            + " LOCAL_BOOTSTRAP_ACCOUNT_LOGIN); the other system administrators are notified by"
            + " mail",
        bootstrapAccount.getId(),
        at);
    List<User> recipients =
        users
            .findByOrganizationIdAndSystemRole(
                bootstrapAccount.getOrganizationId(), SystemRole.SYSTEM_ADMIN)
            .stream()
            .filter(admin -> !admin.getId().equals(bootstrapAccount.getId()))
            .filter(admin -> admin.getEmail() != null && !admin.getEmail().isBlank())
            .toList();
    String occurredAt = OCCURRED_AT.format(at.atZone(clock.getZone()));
    for (User admin : recipients) {
      mail.enqueue(
          MailTemplateKey.BOOTSTRAP_ACCOUNT_USED,
          Locale.GERMAN,
          admin.getEmail(),
          Map.of(
              "displayName",
              admin.getDisplayName() == null ? "" : admin.getDisplayName(),
              "occurredAtHuman",
              occurredAt));
    }
  }
}
