package io.opaa.auth.local;

import io.opaa.api.types.LocalAccountState;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * The reminder to the person fourteen days before an expiry (ADR-0033, Entscheidung 11): every
 * {@code ACTIVE} local account whose expiry falls on the calendar day fourteen days after the day
 * of the run - in the zone of the run, so a daylight-saving change neither doubles nor skips a day
 * - gets {@code ACCOUNT_EXPIRING}. Sent once because each expiry lies on exactly one calendar day;
 * a day on which the run did not happen is a reminder not sent, not one sent twice. The system
 * administrators are told by {@link AdminReviewReminderStep}, which merges the count with the
 * quarterly review. Locked, expired and still invited accounts get no reminder. At most {@link
 * #MAX_MAILS_PER_RUN} mails per run - the daily run holds the one scheduler thread for the whole
 * synchronous send.
 */
@Component
@Order(20)
public class ExpiryReminderStep implements LocalAccountMaintenanceStep {

  public static final int LEAD_DAYS = 14;
  public static final int MAX_MAILS_PER_RUN = 200;

  private static final Logger log = LoggerFactory.getLogger(ExpiryReminderStep.class);

  private final LocalCredentialsRepository credentials;
  private final UserRepository users;
  private final LocalAccountMailer mailer;
  private final ZoneId zone;

  @Autowired
  public ExpiryReminderStep(
      LocalCredentialsRepository credentials, UserRepository users, LocalAccountMailer mailer) {
    this(credentials, users, mailer, ZoneId.systemDefault());
  }

  ExpiryReminderStep(
      LocalCredentialsRepository credentials,
      UserRepository users,
      LocalAccountMailer mailer,
      ZoneId zone) {
    this.credentials = credentials;
    this.users = users;
    this.mailer = mailer;
    this.zone = zone;
  }

  @Override
  public String name() {
    return "expiry-reminder";
  }

  /** The window {@code [from, to)} of the calendar day {@link #LEAD_DAYS} after the run's day. */
  record Window(Instant from, Instant to) {
    boolean contains(Instant instant) {
      return !instant.isBefore(from) && instant.isBefore(to);
    }
  }

  static Window windowOf(Instant now, ZoneId zone) {
    LocalDate day = LocalDate.ofInstant(now, zone).plusDays(LEAD_DAYS);
    return new Window(
        day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant());
  }

  /** The {@code ACTIVE} accounts expiring in the window - shared with the administrators' step. */
  List<LocalCredentials> expiringAccounts(Instant now) {
    Window window = windowOf(now, zone);
    return credentials
        .findByExpiresAtGreaterThanEqualAndExpiresAtLessThan(window.from(), window.to())
        .stream()
        .filter(row -> row.state(now) == LocalAccountState.ACTIVE)
        .toList();
  }

  @Override
  public void run(Instant now) {
    List<LocalCredentials> expiring = expiringAccounts(now);
    if (expiring.isEmpty()) {
      return;
    }
    Map<UUID, User> byId =
        users.findAllById(expiring.stream().map(LocalCredentials::getUserId).toList()).stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
    int sent = 0;
    for (LocalCredentials row : expiring) {
      User user = byId.get(row.getUserId());
      if (user == null) {
        continue;
      }
      if (sent >= MAX_MAILS_PER_RUN) {
        log.warn(
            "Expiry reminder: stopped after {} mails; {} more accounts expire in {} days and get"
                + " no reminder from this run",
            MAX_MAILS_PER_RUN,
            expiring.size() - sent,
            LEAD_DAYS);
        break;
      }
      mailer.sendExpiring(user, row.getExpiresAt());
      sent++;
    }
    log.info("Expiry reminder: {} local account(s) expire in {} days", sent, LEAD_DAYS);
  }
}
