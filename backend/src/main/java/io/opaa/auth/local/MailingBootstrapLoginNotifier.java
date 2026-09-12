package io.opaa.auth.local;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.mail.MailDispatchExecutor;
import io.opaa.mail.MailService;
import io.opaa.mail.MailTemplateKey;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Tells every <em>other</em> system administrator of the organization that the bootstrap account
 * was used (ADR-0033, Entscheidung 5: mail {@code BOOTSTRAP_ACCOUNT_USED}), next to the audit event
 * {@link BootstrapAccountLoginListener} writes. The sends run on the {@link MailDispatchExecutor}:
 * the emergency sign-in must not wait for a mail server, and a failed send is the mail subsystem's
 * business ({@code MailService} logs and records it). One WARN line with the account id - never the
 * address - stays, so an operator reading the log sees the emergency access was used.
 */
@Component
public class MailingBootstrapLoginNotifier implements BootstrapLoginNotifier {

  private static final Logger log = LoggerFactory.getLogger(MailingBootstrapLoginNotifier.class);
  private static final DateTimeFormatter OCCURRED_AT =
      DateTimeFormatter.ofPattern("'am' dd.MM.yyyy 'um' HH:mm 'Uhr'", Locale.GERMANY);

  private final UserRepository users;
  private final MailService mail;
  private final Executor executor;

  @Autowired
  public MailingBootstrapLoginNotifier(
      UserRepository users, MailService mail, MailDispatchExecutor dispatch) {
    this(users, mail, (Executor) dispatch);
  }

  MailingBootstrapLoginNotifier(UserRepository users, MailService mail, Executor executor) {
    this.users = users;
    this.mail = mail;
    this.executor = executor;
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
    String occurredAt = OCCURRED_AT.format(at.atZone(ZoneId.systemDefault()));
    executor.execute(
        () -> {
          for (User admin : recipients) {
            mail.send(
                MailTemplateKey.BOOTSTRAP_ACCOUNT_USED,
                Locale.GERMAN,
                admin.getEmail(),
                Map.of(
                    "displayName",
                    admin.getDisplayName() == null ? "" : admin.getDisplayName(),
                    "occurredAtHuman",
                    occurredAt));
          }
        });
  }
}
