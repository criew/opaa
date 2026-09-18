package io.opaa.externalaccess;

import io.opaa.api.types.NotificationType;
import io.opaa.notification.NotificationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes the mass-retrieval alerts of {@link ExternalAccessMassRetrievalAlarm} after a short,
 * fixed period - read or unread (#1720, docs/features/external-access.md, "Kontingente und der
 * Abflussalarm").
 *
 * <p>This closes the one gap between the alert and the specification's promise. The count itself
 * lives in memory and is gone with its window, but the message has to be persisted or nobody would
 * ever see it - and a notification that stays forever is, sorted by its timestamp, precisely the
 * per-token history the specification rules out. The period is a constant, not a setting: it is not
 * an operating parameter but the bound that makes the alert compatible with "kein Verlauf". Acting
 * on an alert is a matter of days; anything beyond that is a record of behaviour.
 */
@Component
public class ExternalAccessAlertRetention {

  /** How long an alert stays readable. A constant by decision - see the class comment. */
  public static final Duration RETENTION = Duration.ofDays(14);

  private static final Logger log = LoggerFactory.getLogger(ExternalAccessAlertRetention.class);

  private final NotificationService notifications;
  private final Clock clock;

  public ExternalAccessAlertRetention(NotificationService notifications, Clock clock) {
    this.notifications = notifications;
    this.clock = clock;
  }

  /** Hourly, so an alert is never kept materially longer than {@link #RETENTION}. */
  @Scheduled(cron = "0 20 * * * *")
  public void deleteExpiredAlerts() {
    Instant cutoff = clock.instant().minus(RETENTION);
    int deleted =
        notifications.deleteOlderThan(NotificationType.EXTERNAL_ACCESS_MASS_RETRIEVAL, cutoff);
    if (deleted > 0) {
      log.info("Deleted {} external access mass-retrieval alert(s) older than {}", deleted, cutoff);
    }
  }
}
