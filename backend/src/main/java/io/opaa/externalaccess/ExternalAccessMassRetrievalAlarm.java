package io.opaa.externalaccess;

import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.NotificationType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.notification.NotificationService;
import io.opaa.ratelimit.InstallationCounters;
import io.opaa.search.MassRetrievalAlarm;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * The mass-retrieval alert of the external-access channel (docs/features/external-access.md,
 * "Kontingente und der Abflussalarm"): counts the retrievals of the <b>whole channel</b> in a
 * sliding window and, when the threshold from the channel settings is exceeded, raises exactly one
 * message to the system administration plus one line in the technical application log.
 *
 * <p>What it deliberately is not. The count is <b>one counter of the whole installation</b> in
 * {@link InstallationCounters} (ADR-0053, Entscheidung 12): one row for the channel, with the
 * events of the current and the previous hour and the cooldown, without any token, person or
 * history - every instance counts into it and sees what the others counted, and the cooldown lets
 * exactly one of them raise the alert. It writes <b>no entry into the Nachweisprotokoll</b> - the
 * alert is a security event and belongs into security monitoring, the same shape a failed sign-in
 * already has; what reaches the audit trail is only the token suspension that may follow. It is
 * <b>not per person</b>: the message names the token id, without which the access could not be
 * switched off at all, and nothing is ever aggregated into a per-person evaluation.
 *
 * <p>A cooldown follows every alert. Without it a sustained event would decay into a series of
 * messages, and a series of messages is a history by another name.
 *
 * <p><b>The message is kept, but only briefly.</b> An alert nobody can read is no alert, so it is a
 * persisted notification - and a notification that stays forever would be exactly the per-token
 * history the specification rules out. {@link ExternalAccessAlertRetention} therefore deletes it
 * after {@link ExternalAccessAlertRetention#RETENTION} regardless of whether it was read. For the
 * same reason the message carries the token id and the time window and <b>not the measured
 * value</b>: the number is what would turn a series of messages into a usage curve, and the
 * technical application log holds it for the incident itself.
 */
@Component
public class ExternalAccessMassRetrievalAlarm implements MassRetrievalAlarm {

  private static final Logger log = LoggerFactory.getLogger(ExternalAccessMassRetrievalAlarm.class);

  static final Duration WINDOW = Duration.ofHours(1);

  /** After an alert, the channel stays quiet for this long - see the class comment. */
  static final Duration COOLDOWN = Duration.ofHours(6);

  private static final String TITLE = "Auffällig viele Abrufe durch externe KI-Werkzeuge";

  /** The one counter of the channel; it names no token and no person. */
  static final String COUNTER_KEY = "external-access:retrievals";

  private final ExternalAccessSettingsService settings;
  private final UserRepository users;
  private final NotificationService notifications;
  private final InstallationCounters counters;
  private final Clock clock;

  public ExternalAccessMassRetrievalAlarm(
      ExternalAccessSettingsService settings,
      UserRepository users,
      NotificationService notifications,
      InstallationCounters counters,
      Clock clock) {
    this.settings = settings;
    this.users = users;
    this.notifications = notifications;
    this.counters = counters;
    this.clock = clock;
  }

  /**
   * Counts one retrieval of the channel. A {@code null} token is a signed-in person on the regular
   * REST endpoint and counts for nothing: the alert watches the external-access channel, not the
   * web interface, and a person's work is not what it is meant to notice.
   */
  @Override
  public void record(UUID organizationId, UUID accessTokenId) {
    if (accessTokenId == null) {
      return;
    }
    int threshold = settings.current().values().massRetrievalAlertThreshold();
    double measured;
    try {
      measured = counters.count(COUNTER_KEY, WINDOW);
      if (measured <= threshold || !counters.claimQuietPeriod(COUNTER_KEY, WINDOW, COOLDOWN)) {
        return;
      }
    } catch (DataAccessException | TransactionException e) {
      // The retrieval itself stands; the alert cannot count it, which the log says.
      log.warn("External access mass-retrieval counter unavailable: {}", e.getMessage());
      return;
    }
    raise(organizationId, accessTokenId, threshold, (long) Math.ceil(measured), clock.instant());
  }

  private void raise(
      UUID organizationId, UUID accessTokenId, int threshold, long measured, Instant at) {
    log.warn(
        "External access mass-retrieval threshold exceeded: {} retrievals within {} minutes,"
            + " threshold {}, access token {}",
        measured,
        WINDOW.toMinutes(),
        threshold,
        accessTokenId);
    // Token id and time window, deliberately without the measured value: the number is what would
    // turn a sequence of kept messages into a usage curve per token. It stands in the application
    // log, which is where the incident is investigated.
    String body =
        "Der Zugriff externer KI-Werkzeuge hat die Schwelle für Massenabrufe in der Stunde bis "
            + at
            + " überschritten. Betroffenes Zugangstoken: "
            + accessTokenId
            + ". Bitte prüfen Sie den Vorfall und sperren Sie den Zugang, falls nötig. Diese"
            + " Meldung wird nach "
            + ExternalAccessAlertRetention.RETENTION.toDays()
            + " Tagen automatisch gelöscht.";
    List<User> administrators =
        users.findByOrganizationIdAndSystemRole(organizationId, SystemRole.SYSTEM_ADMIN);
    for (User administrator : administrators) {
      notifications.notify(
          organizationId,
          administrator.getId(),
          NotificationType.EXTERNAL_ACCESS_MASS_RETRIEVAL,
          AuditObjectType.SYSTEM_SETTING,
          accessTokenId,
          TITLE,
          body);
    }
  }

  /**
   * Forgets the window and the cooldown - for a test, and for nothing else. Public because the
   * window is one counter of the installation: a class that drives the alert has to leave it as it
   * found it, and one that asserts on it has to start from a known state, whichever package it sits
   * in.
   */
  public void reset() {
    counters.reset(COUNTER_KEY, WINDOW);
  }
}
