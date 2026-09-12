package io.opaa.auth.local;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
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

/**
 * The one review reminder to the system administrators per run (ADR-0033, Entscheidung 11): {@code
 * ADMIN_REVIEW_REMINDER} with the number of accounts waiting for their look and the link to the
 * list - no names. The number merges what the day brings: the accounts expiring in fourteen days
 * (the window of {@link ExpiryReminderStep}) and, on the first day of a quarter, every account
 * without an expiry date (the bootstrap account left out - having no expiry is its purpose). One
 * mail per administrator per organization, never two with different numbers on the same morning. A
 * first of the quarter on which the run did not happen is a reminder not sent. At most {@link
 * #MAX_MAILS_PER_RUN} mails per run.
 */
@Component
@Order(30)
public class AdminReviewReminderStep implements LocalAccountMaintenanceStep {

  public static final int MAX_MAILS_PER_RUN = 200;

  private static final Logger log = LoggerFactory.getLogger(AdminReviewReminderStep.class);

  private final UserRepository users;
  private final LocalCredentialsRepository credentials;
  private final ExpiryReminderStep expiryReminder;
  private final LocalAccountMailer mailer;
  private final ZoneId zone;

  @Autowired
  public AdminReviewReminderStep(
      UserRepository users,
      LocalCredentialsRepository credentials,
      ExpiryReminderStep expiryReminder,
      LocalAccountMailer mailer) {
    this(users, credentials, expiryReminder, mailer, ZoneId.systemDefault());
  }

  AdminReviewReminderStep(
      UserRepository users,
      LocalCredentialsRepository credentials,
      ExpiryReminderStep expiryReminder,
      LocalAccountMailer mailer,
      ZoneId zone) {
    this.users = users;
    this.credentials = credentials;
    this.expiryReminder = expiryReminder;
    this.mailer = mailer;
    this.zone = zone;
  }

  @Override
  public String name() {
    return "admin-review-reminder";
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
    if (isQuarterStart(LocalDate.ofInstant(now, zone))) {
      for (LocalCredentials row : credentials.findAllById(byId.keySet())) {
        User user = byId.get(row.getUserId());
        if (row.isBootstrap() || row.getExpiresAt() != null || user == null) {
          continue;
        }
        countPerOrganization.merge(user.getOrganizationId(), 1L, Long::sum);
      }
    }
    int sent = 0;
    for (Map.Entry<UUID, Long> entry : countPerOrganization.entrySet()) {
      if (entry.getValue() == 0) {
        continue;
      }
      List<User> admins =
          users.findByOrganizationIdAndSystemRole(entry.getKey(), SystemRole.SYSTEM_ADMIN).stream()
              .filter(admin -> admin.getEmail() != null && !admin.getEmail().isBlank())
              .toList();
      for (User admin : admins) {
        if (sent >= MAX_MAILS_PER_RUN) {
          log.warn(
              "Admin review reminder: stopped after {} mails; further administrators get none"
                  + " from this run",
              MAX_MAILS_PER_RUN);
          return;
        }
        mailer.sendReviewReminder(admin, entry.getValue());
        sent++;
      }
    }
    if (sent > 0) {
      log.info(
          "Admin review reminder: {} mail(s) for {} organization(s)",
          sent,
          countPerOrganization.size());
    }
  }
}
