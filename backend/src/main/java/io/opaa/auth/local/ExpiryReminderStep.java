package io.opaa.auth.local;

import io.opaa.api.types.LocalAccountState;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import java.time.Duration;
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
 * quarterly review. Locked, expired and still invited accounts get no reminder.
 *
 * <p>The run is bounded by time, not by a number of mails: the daily run holds the one scheduler
 * thread for the whole synchronous send, so after {@link #TIME_BUDGET} since the step began it
 * stops with an ERROR line naming how many accounts got no reminder. Unlike the inactivity lock,
 * which catches up on the next day, a reminder not sent today is not sent tomorrow - the window has
 * moved on - which is why the budget is generous and the abort is an error, not a warning.
 */
@Component
@Order(20)
public class ExpiryReminderStep implements LocalAccountMaintenanceStep {

  public static final int LEAD_DAYS = 14;
  public static final Duration TIME_BUDGET = Duration.ofMinutes(5);

  private static final Logger log = LoggerFactory.getLogger(ExpiryReminderStep.class);

  private final LocalCredentialsRepository credentials;
  private final UserRepository users;
  private final LocalAccountMailer mailer;
  private final ZoneId zone;
  private final Duration budget;

  @Autowired
  public ExpiryReminderStep(
      LocalCredentialsRepository credentials, UserRepository users, LocalAccountMailer mailer) {
    this(credentials, users, mailer, ZoneId.systemDefault(), TIME_BUDGET);
  }

  ExpiryReminderStep(
      LocalCredentialsRepository credentials,
      UserRepository users,
      LocalAccountMailer mailer,
      ZoneId zone,
      Duration budget) {
    this.credentials = credentials;
    this.users = users;
    this.mailer = mailer;
    this.zone = zone;
    this.budget = budget;
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
    long started = System.nanoTime();
    int sent = 0;
    int skipped = 0;
    for (LocalCredentials row : expiring) {
      User user = byId.get(row.getUserId());
      if (user == null) {
        continue;
      }
      if (MaintenanceBudget.exhausted(started, budget)) {
        skipped = expiring.size() - sent;
        log.error(
            "Expiry reminder: stopped after {} because {} mails took longer than the budget of {};"
                + " {} account(s) expiring in {} days get no reminder - the window will have moved"
                + " on tomorrow",
            budget,
            sent,
            budget,
            skipped,
            LEAD_DAYS);
        break;
      }
      mailer.sendExpiring(user, row.getExpiresAt());
      sent++;
    }
    log.info(
        "Expiry reminder: {} local account(s) expire in {} days, {} reminded, {} not",
        expiring.size(),
        LEAD_DAYS,
        sent,
        skipped);
  }
}
