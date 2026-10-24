package io.opaa.externalaccess.token;

import io.opaa.account.LocalAccountMaintenanceStep;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConditionalOnFeature;
import io.opaa.common.Feature;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The reminder before a token expires (docs/features/external-access.md, "Eigenschaften"): every
 * live token whose expiry falls on the calendar day {@code n} days after the day of the run gets a
 * mail, for each configured lead time - 14 and 3 days by default.
 *
 * <p>Without it every token dies silently and the failure lands as a ticket with an operator who
 * cannot do anything about it: only the person can issue a new one, and an expired token cannot be
 * extended.
 *
 * <p>Sent once per lead time because an expiry falls on exactly one calendar day, computed in the
 * zone of the run so a daylight-saving change neither doubles nor skips a day, and because the
 * reminder is claimed in {@code external_access_tokens.expiry_reminded_days} in the transaction
 * that queues the mail, so a second run - on another instance - finds it claimed. A day on which
 * the run did not happen is a reminder not sent, not one sent twice - the same trade-off {@code
 * ExpiryReminderStep} makes for accounts.
 */
@ConditionalOnFeature(Feature.EXTERNAL_ACCESS)
@Component
@Order(41)
public class ExternalAccessTokenExpiryReminderStep implements LocalAccountMaintenanceStep {

  /** The two lead times the specification names, in descending order. */
  static final List<Integer> LEAD_DAYS = List.of(14, 3);

  private static final Logger log =
      LoggerFactory.getLogger(ExternalAccessTokenExpiryReminderStep.class);

  private final ExternalAccessTokenRepository tokens;
  private final UserRepository users;
  private final ExternalAccessTokenMailer mailer;
  private final ExternalAccessSettingsService settings;
  private final TransactionOperations transactions;
  private final ZoneId zone;

  @Autowired
  public ExternalAccessTokenExpiryReminderStep(
      ExternalAccessTokenRepository tokens,
      UserRepository users,
      ExternalAccessTokenMailer mailer,
      ExternalAccessSettingsService settings,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this(
        tokens,
        users,
        mailer,
        settings,
        new TransactionTemplate(transactionManager),
        clock.getZone());
  }

  ExternalAccessTokenExpiryReminderStep(
      ExternalAccessTokenRepository tokens,
      UserRepository users,
      ExternalAccessTokenMailer mailer,
      ExternalAccessSettingsService settings,
      TransactionOperations transactions,
      ZoneId zone) {
    this.tokens = tokens;
    this.users = users;
    this.mailer = mailer;
    this.settings = settings;
    this.transactions = transactions;
    this.zone = zone;
  }

  private record Due(ExternalAccessToken token, int leadDays) {}

  @Override
  public String name() {
    return "external-access-token-expiry-reminder";
  }

  @Override
  public void run(Instant now) {
    if (!settings.isEnabled()) {
      // A closed channel needs no reminder: the token it warns about cannot be used either way,
      // and "Ihr Zugangstoken läuft ab" would be an odd thing to read while the channel is off.
      return;
    }
    Map<UUID, List<Due>> due = new LinkedHashMap<>();
    for (int leadDays : LEAD_DAYS) {
      LocalDate day = LocalDate.ofInstant(now, zone).plusDays(leadDays);
      List<ExternalAccessToken> expiring =
          tokens.findExpiringBetween(
              day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant());
      for (ExternalAccessToken token : expiring) {
        due.computeIfAbsent(token.getUserId(), id -> new ArrayList<>())
            .add(new Due(token, leadDays));
      }
    }
    if (due.isEmpty()) {
      return;
    }
    Map<UUID, User> owners =
        users.findAllById(due.keySet()).stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
    int sent = 0;
    for (Map.Entry<UUID, List<Due>> entry : due.entrySet()) {
      User owner = owners.get(entry.getKey());
      if (owner == null) {
        continue;
      }
      for (Due reminder : entry.getValue()) {
        if (remindOnce(owner, reminder)) {
          sent++;
        }
      }
    }
    log.info("External access tokens: {} expiry reminder(s) sent", sent);
  }

  /** Claims the reminder and queues the mail in one transaction; false when already claimed. */
  private boolean remindOnce(User owner, Due reminder) {
    ExternalAccessToken token = reminder.token();
    return Boolean.TRUE.equals(
        transactions.execute(
            status -> {
              if (tokens.claimExpiryReminder(token.getId(), reminder.leadDays()) == 0) {
                return false;
              }
              mailer.sendExpiring(owner, token.getName(), token.getExpiresAt());
              return true;
            }));
  }
}
