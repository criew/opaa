package io.opaa.auth.local;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The quarterly review reminder (ADR-0033, Entscheidung 11): on the first day of a quarter the
 * system administrators of every organization with local accounts without an expiry date get {@code
 * ADMIN_REVIEW_REMINDER} with that number and the link to the list - no names. The bootstrap
 * account is not counted (having no expiry is its purpose). A first of the quarter on which the run
 * did not happen is a reminder not sent; the next quarter's run sends again.
 */
@Component
@Order(30)
public class QuarterlyReviewReminderStep implements LocalAccountMaintenanceStep {

  private static final Logger log = LoggerFactory.getLogger(QuarterlyReviewReminderStep.class);

  private final UserRepository users;
  private final LocalCredentialsRepository credentials;
  private final LocalAccountMailer mailer;
  private final ZoneId zone;

  @Autowired
  public QuarterlyReviewReminderStep(
      UserRepository users, LocalCredentialsRepository credentials, LocalAccountMailer mailer) {
    this(users, credentials, mailer, ZoneId.systemDefault());
  }

  QuarterlyReviewReminderStep(
      UserRepository users,
      LocalCredentialsRepository credentials,
      LocalAccountMailer mailer,
      ZoneId zone) {
    this.users = users;
    this.credentials = credentials;
    this.mailer = mailer;
    this.zone = zone;
  }

  @Override
  public String name() {
    return "quarterly-review-reminder";
  }

  static boolean isQuarterStart(LocalDate date) {
    return date.getDayOfMonth() == 1 && (date.getMonthValue() - 1) % 3 == 0;
  }

  @Override
  public void run(Instant now) {
    if (!isQuarterStart(LocalDate.ofInstant(now, zone))) {
      return;
    }
    Map<UUID, Long> withoutExpiryPerOrganization = new HashMap<>();
    for (User user : users.findByIssuer(LocalIssuer.URN)) {
      LocalCredentials row = credentials.findById(user.getId()).orElse(null);
      if (row == null || row.isBootstrap() || row.getExpiresAt() != null) {
        continue;
      }
      withoutExpiryPerOrganization.merge(user.getOrganizationId(), 1L, Long::sum);
    }
    withoutExpiryPerOrganization.forEach(
        (organizationId, count) ->
            users
                .findByOrganizationIdAndSystemRole(organizationId, SystemRole.SYSTEM_ADMIN)
                .stream()
                .filter(admin -> admin.getEmail() != null && !admin.getEmail().isBlank())
                .forEach(admin -> mailer.sendReviewReminder(admin, count)));
    log.info(
        "Quarterly review reminder sent for {} organization(s)",
        withoutExpiryPerOrganization.size());
  }
}
