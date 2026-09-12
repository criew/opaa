package io.opaa.auth.local;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The reminder fourteen days before an expiry (ADR-0033, Entscheidung 11): the person gets {@code
 * ACCOUNT_EXPIRING}, the system administrators of the organization a review reminder with the
 * number of accounts expiring - no names. Sent once because the daily run looks at the window
 * {@code [now + 13 d, now + 14 d)} only: each expiry falls into exactly one day's window. A day on
 * which the run did not happen is a reminder not sent, not one sent twice; no marker column is
 * needed for that.
 */
@Component
@Order(20)
public class ExpiryReminderStep implements LocalAccountMaintenanceStep {

  public static final Duration LEAD = Duration.ofDays(14);

  private static final Logger log = LoggerFactory.getLogger(ExpiryReminderStep.class);

  private final LocalCredentialsRepository credentials;
  private final UserRepository users;
  private final LocalAccountMailer mailer;

  public ExpiryReminderStep(
      LocalCredentialsRepository credentials, UserRepository users, LocalAccountMailer mailer) {
    this.credentials = credentials;
    this.users = users;
    this.mailer = mailer;
  }

  @Override
  public String name() {
    return "expiry-reminder";
  }

  @Override
  public void run(Instant now) {
    Instant from = now.plus(LEAD).minus(Duration.ofDays(1));
    Instant to = now.plus(LEAD);
    Map<UUID, Long> expiringPerOrganization = new HashMap<>();
    for (LocalCredentials row :
        credentials.findByExpiresAtGreaterThanEqualAndExpiresAtLessThan(from, to)) {
      User user = users.findById(row.getUserId()).orElse(null);
      if (user == null) {
        continue;
      }
      mailer.sendExpiring(user, row.getExpiresAt());
      expiringPerOrganization.merge(user.getOrganizationId(), 1L, Long::sum);
    }
    expiringPerOrganization.forEach(
        (organizationId, count) ->
            users
                .findByOrganizationIdAndSystemRole(organizationId, SystemRole.SYSTEM_ADMIN)
                .stream()
                .filter(admin -> admin.getEmail() != null && !admin.getEmail().isBlank())
                .forEach(admin -> mailer.sendReviewReminder(admin, count)));
    if (!expiringPerOrganization.isEmpty()) {
      log.info(
          "Expiry reminder: {} local account(s) expire in {} days",
          expiringPerOrganization.values().stream().mapToLong(Long::longValue).sum(),
          LEAD.toDays());
    }
  }
}
