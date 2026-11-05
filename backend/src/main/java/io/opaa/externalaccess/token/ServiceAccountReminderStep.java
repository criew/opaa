package io.opaa.externalaccess.token;

import io.opaa.account.LocalAccountMaintenanceStep;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.AccountUsability;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConditionalOnFeature;
import io.opaa.common.Feature;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.library.ExternalAccessReminderLead;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The reminders of a service account (ADR-0035, Entscheidung 7): before one of its tokens expires -
 * the lead times of a personal token - and before a library's consent to it ends - the lead of the
 * Fremdzugangsfreigabe, a setting of the external AI tools. Each goes to the responsible person and
 * to the system administration of the account's organization; without a usable responsible person,
 * to the administration alone.
 *
 * <p>Sent once per token and lead, and once per consent row: the claim is written in the
 * transaction that queues the mails, so a second run - on another instance - finds it claimed. A
 * moved end is a new consent row and earns a fresh reminder.
 */
@ConditionalOnFeature(Feature.EXTERNAL_ACCESS)
@Component
@Order(43)
public class ServiceAccountReminderStep implements LocalAccountMaintenanceStep {

  private static final Logger log = LoggerFactory.getLogger(ServiceAccountReminderStep.class);

  private final ServiceAccountTokenRepository tokens;
  private final ServiceAccountLibraryConsentRepository consents;
  private final ServiceAccountRepository accounts;
  private final KnowledgeLibraryRepository libraries;
  private final UserRepository users;
  private final AccountUsability usability;
  private final ServiceAccountMailer mailer;
  private final ExternalAccessSettingsService settings;
  private final ExternalAccessReminderLead consentLead;
  private final TransactionOperations transactions;
  private final ZoneId zone;

  @Autowired
  public ServiceAccountReminderStep(
      ServiceAccountTokenRepository tokens,
      ServiceAccountLibraryConsentRepository consents,
      ServiceAccountRepository accounts,
      KnowledgeLibraryRepository libraries,
      UserRepository users,
      AccountUsability usability,
      ServiceAccountMailer mailer,
      ExternalAccessSettingsService settings,
      ExternalAccessReminderLead consentLead,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.tokens = tokens;
    this.consents = consents;
    this.accounts = accounts;
    this.libraries = libraries;
    this.users = users;
    this.usability = usability;
    this.mailer = mailer;
    this.settings = settings;
    this.consentLead = consentLead;
    this.transactions = new TransactionTemplate(transactionManager);
    this.zone = clock.getZone();
  }

  @Override
  public String name() {
    return "service-account-reminder";
  }

  @Override
  public void run(Instant now) {
    if (!settings.isEnabled()) {
      // A closed channel needs no reminder, as for a personal token.
      return;
    }
    int sent = remindTokens(now) + remindConsents(now);
    if (sent > 0) {
      log.info("Service accounts: {} expiry reminder(s) sent", sent);
    }
  }

  private int remindTokens(Instant now) {
    int sent = 0;
    for (int leadDays : ExternalAccessTokenExpiryReminderStep.LEAD_DAYS) {
      LocalDate day = LocalDate.ofInstant(now, zone).plusDays(leadDays);
      for (ServiceAccountToken token :
          tokens.findExpiringBetween(
              day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant())) {
        Optional<ServiceAccount> account =
            accounts.findById(token.getServiceAccountId()).filter(ServiceAccount::isActive);
        if (account.isEmpty()) {
          continue;
        }
        Boolean claimed =
            transactions.execute(
                status -> {
                  if (tokens.claimExpiryReminder(token.getId(), leadDays) == 0) {
                    return false;
                  }
                  for (User recipient : recipientsOf(account.get())) {
                    mailer.sendTokenExpiring(
                        recipient, account.get().getName(), token.getName(), token.getExpiresAt());
                  }
                  return true;
                });
        if (Boolean.TRUE.equals(claimed)) {
          sent++;
        }
      }
    }
    return sent;
  }

  private int remindConsents(Instant now) {
    int leadDays = consentLead.days();
    if (leadDays <= 0) {
      return 0;
    }
    int sent = 0;
    for (ServiceAccountLibraryConsent consent :
        consents.findExpiringBetween(now, now.plus(Duration.ofDays(leadDays)))) {
      Optional<ServiceAccount> account = accounts.findById(consent.getServiceAccountId());
      Optional<KnowledgeLibrary> library = libraries.findById(consent.getLibraryId());
      if (account.isEmpty() || library.isEmpty()) {
        continue;
      }
      Boolean claimed =
          transactions.execute(
              status -> {
                if (consents.claimReminder(consent.getId(), now) == 0) {
                  return false;
                }
                for (User recipient : recipientsOf(account.get())) {
                  mailer.sendConsentExpiring(
                      recipient,
                      account.get().getName(),
                      library.get().getName(),
                      consent.getExpiresAt());
                }
                return true;
              });
      if (Boolean.TRUE.equals(claimed)) {
        sent++;
      }
    }
    return sent;
  }

  /**
   * The responsible person while her account is usable, and every usable system administrator of
   * the account's organization - each address once.
   */
  List<User> recipientsOf(ServiceAccount account) {
    Map<UUID, User> recipients = new LinkedHashMap<>();
    if (account.getResponsibleUserId() != null) {
      users
          .findById(account.getResponsibleUserId())
          .filter(user -> usability.stateOf(user).isUsable())
          .ifPresent(user -> recipients.put(user.getId(), user));
    }
    for (User administrator :
        users.findByOrganizationIdAndSystemRole(
            account.getOrganizationId(), SystemRole.SYSTEM_ADMIN)) {
      if (usability.stateOf(administrator).isUsable()) {
        recipients.putIfAbsent(administrator.getId(), administrator);
      }
    }
    return new ArrayList<>(recipients.values());
  }
}
