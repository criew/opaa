package io.opaa.externalaccess;

import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.NotificationType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.notification.NotificationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The mass-retrieval alert of the external-access channel (#1720, docs/features/external-access.md,
 * "Kontingente und der Abflussalarm"): counts the retrievals of the <b>whole channel</b> in a
 * sliding window and, when the threshold from the channel settings is exceeded, raises exactly one
 * message to the system administration plus one line in the technical application log.
 *
 * <p>What it deliberately is not. The count lives <b>in memory only</b> (ADR-0021, single
 * instance): no table, no time series, no history, and it does not survive a restart. It writes
 * <b>no entry into the Nachweisprotokoll</b> - the alert is a security event and belongs into
 * security monitoring, the same shape a failed sign-in already has; what reaches the audit trail is
 * only the token suspension that may follow (#1718). It is <b>not per person</b>: the message names
 * the token id, without which the access could not be switched off at all, and nothing is ever
 * aggregated into a per-person evaluation.
 *
 * <p>A cooldown follows every alert. Without it a sustained event would decay into a series of
 * messages, and a series of messages is a history by another name.
 */
@Component
public class ExternalAccessMassRetrievalAlarm {

  private static final Logger log = LoggerFactory.getLogger(ExternalAccessMassRetrievalAlarm.class);

  static final Duration WINDOW = Duration.ofHours(1);

  /** After an alert, the channel stays quiet for this long - see the class comment. */
  static final Duration COOLDOWN = Duration.ofHours(6);

  private static final String TITLE = "Auffällig viele Abrufe über Fremdzugänge";

  private final ExternalAccessSettingsService settings;
  private final UserRepository users;
  private final NotificationService notifications;
  private final Clock clock;

  /** The channel-wide window. Guarded by its own monitor; nothing else reads it. */
  private final Deque<Long> retrievals = new ArrayDeque<>();

  private long quietUntil;

  public ExternalAccessMassRetrievalAlarm(
      ExternalAccessSettingsService settings,
      UserRepository users,
      NotificationService notifications,
      Clock clock) {
    this.settings = settings;
    this.users = users;
    this.notifications = notifications;
    this.clock = clock;
  }

  /**
   * Counts one retrieval of the channel. A {@code null} token is a signed-in person on the regular
   * REST endpoint and counts for nothing: the alert watches the external-access channel, not the
   * web interface, and a person's work is not what it is meant to notice.
   */
  public void record(UUID organizationId, UUID accessTokenId) {
    if (accessTokenId == null) {
      return;
    }
    int threshold = settings.current().values().massRetrievalAlertThreshold();
    long now = clock.millis();
    int measured;
    synchronized (retrievals) {
      long cutoff = now - WINDOW.toMillis();
      while (!retrievals.isEmpty() && retrievals.peekFirst() < cutoff) {
        retrievals.pollFirst();
      }
      retrievals.addLast(now);
      measured = retrievals.size();
      if (measured <= threshold || now < quietUntil) {
        return;
      }
      quietUntil = now + COOLDOWN.toMillis();
    }
    raise(organizationId, accessTokenId, threshold, measured, Instant.ofEpochMilli(now));
  }

  private void raise(
      UUID organizationId, UUID accessTokenId, int threshold, int measured, Instant at) {
    log.warn(
        "External access mass-retrieval threshold exceeded: {} retrievals within {} minutes,"
            + " threshold {}, access token {}",
        measured,
        WINDOW.toMinutes(),
        threshold,
        accessTokenId);
    String body =
        "Der Fremdzugangskanal hat die Schwelle von "
            + threshold
            + " Abrufen je Stunde überschritten: "
            + measured
            + " Abrufe bis "
            + at
            + ". Betroffenes Zugangstoken: "
            + accessTokenId
            + ". Bitte prüfen Sie den Vorfall und sperren Sie den Zugang, falls nötig.";
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

  /** Forgets the window and the cooldown - for a test, and for nothing else. */
  void reset() {
    synchronized (retrievals) {
      retrievals.clear();
      quietUntil = 0;
    }
  }
}
