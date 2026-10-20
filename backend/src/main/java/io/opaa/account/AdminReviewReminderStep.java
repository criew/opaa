package io.opaa.account;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one review reminder to the system administrators per run (ADR-0033, Entscheidung 11): {@code
 * ADMIN_REVIEW_REMINDER} with the number of accounts waiting for their look and the link to the
 * list - no names. The number merges what the day brings: the accounts expiring in fourteen days
 * (the window of {@link ExpiryReminderStep}) and, on the first day of a quarter, every account
 * without an expiry date (the bootstrap account left out - having no expiry is its purpose). One
 * mail per administrator per organization, never two with different numbers on the same morning:
 * the day is claimed per organization in {@code admin_review_reminders} in the transaction that
 * queues the mails, so a second run - on another instance - finds it claimed. A first of the
 * quarter on which the run did not happen is a reminder not sent. Bounded by {@link #TIME_BUDGET}
 * of sending like {@link ExpiryReminderStep}, with an ERROR line on abort; the inactivity lock is
 * the one step that catches up on the next day, the reminders are not.
 */
@Component
@Order(30)
public class AdminReviewReminderStep implements LocalAccountMaintenanceStep {

  public static final Duration TIME_BUDGET = Duration.ofMinutes(5);

  private static final Logger log = LoggerFactory.getLogger(AdminReviewReminderStep.class);

  private final UserRepository users;
  private final LocalCredentialsRepository credentials;
  private final ExpiryReminderStep expiryReminder;
  private final LocalAccountMailer mailer;
  private final JdbcOperations jdbc;
  private final TransactionOperations transactions;
  private final Clock clock;

  public AdminReviewReminderStep(
      UserRepository users,
      LocalCredentialsRepository credentials,
      ExpiryReminderStep expiryReminder,
      LocalAccountMailer mailer,
      JdbcOperations jdbc,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.users = users;
    this.credentials = credentials;
    this.expiryReminder = expiryReminder;
    this.mailer = mailer;
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public String name() {
    return "admin-review-reminder";
  }

  /**
   * Claims the day for the organization and queues one mail per system administrator in the same
   * transaction; 0 when the day is already claimed.
   */
  private int remindOnce(UUID organizationId, long count, LocalDate today) {
    Integer queued =
        transactions.execute(
            status -> {
              int claimed =
                  jdbc.update(
                      "INSERT INTO admin_review_reminders (organization_id, reminded_on)"
                          + " VALUES (?, ?) ON CONFLICT (organization_id) DO UPDATE"
                          + " SET reminded_on = EXCLUDED.reminded_on"
                          + " WHERE admin_review_reminders.reminded_on < EXCLUDED.reminded_on",
                      organizationId,
                      today);
              if (claimed == 0) {
                return 0;
              }
              List<User> admins =
                  users
                      .findByOrganizationIdAndSystemRole(organizationId, SystemRole.SYSTEM_ADMIN)
                      .stream()
                      .filter(admin -> admin.getEmail() != null && !admin.getEmail().isBlank())
                      .toList();
              admins.forEach(admin -> mailer.sendReviewReminder(admin, count));
              return admins.size();
            });
    return queued == null ? 0 : queued;
  }

  static boolean isQuarterStart(LocalDate date) {
    return date.getDayOfMonth() == 1 && (date.getMonthValue() - 1) % 3 == 0;
  }

  @Override
  public void run(Instant now) {
    List<User> localUsers = users.findByIssuer(LocalIssuer.URN);
    Map<UUID, User> byId =
        localUsers.stream().collect(Collectors.toMap(User::getId, Function.identity()));
    Map<UUID, Long> countPerOrganization = new HashMap<>();
    for (LocalCredentials row : expiryReminder.expiringAccounts(now)) {
      User user = byId.get(row.getUserId());
      if (user != null) {
        countPerOrganization.merge(user.getOrganizationId(), 1L, Long::sum);
      }
    }
    if (isQuarterStart(LocalDate.ofInstant(now, clock.getZone()))) {
      for (LocalCredentials row : credentials.findAllById(byId.keySet())) {
        User user = byId.get(row.getUserId());
        if (user == null || !row.countsAsWithoutExpiry()) {
          continue;
        }
        countPerOrganization.merge(user.getOrganizationId(), 1L, Long::sum);
      }
    }
    LocalDate today = LocalDate.ofInstant(now, clock.getZone());
    long started = System.nanoTime();
    int sent = 0;
    for (Map.Entry<UUID, Long> entry : countPerOrganization.entrySet()) {
      if (entry.getValue() == 0) {
        continue;
      }
      if (MaintenanceBudget.exhausted(started, TIME_BUDGET)) {
        log.error(
            "Admin review reminder: stopped after {} because {} mails took longer than the"
                + " budget; further organizations get no reminder from this run",
            TIME_BUDGET,
            sent);
        return;
      }
      sent += remindOnce(entry.getKey(), entry.getValue(), today);
    }
    if (sent > 0) {
      log.info(
          "Admin review reminder: {} mail(s) for {} organization(s)",
          sent,
          countPerOrganization.size());
    }
  }
}
