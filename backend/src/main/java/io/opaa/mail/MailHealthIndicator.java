package io.opaa.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether mail is currently working (#1536, ADR-0033 Entscheidung 10): {@code DOWN} when
 * the last send attempt failed, {@code UP} when the last one succeeded, {@code UNKNOWN} while SMTP
 * is not configured or nothing has been sent yet.
 *
 * <p><b>State without details, deliberately.</b> A monitoring system needs to know that mail is
 * failing; the cause belongs on the settings page, behind {@code SYSTEM_ADMIN}, and not in an
 * endpoint whose details are visible to every signed-in caller. "Not configured" is {@code UNKNOWN}
 * rather than {@code DOWN} because a deployment without a mail server is a supported configuration
 * - and {@code UNKNOWN} does not pull the overall status down.
 *
 * <p>Reads the in-memory status {@link MailSettingsService} keeps, so a scrape costs no query.
 */
@Component("mail")
@ConditionalOnProperty(name = "management.health.mail.enabled", matchIfMissing = true)
public class MailHealthIndicator implements HealthIndicator {

  private final MailSettingsService settingsService;

  public MailHealthIndicator(MailSettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @Override
  public Health health() {
    if (!settingsService.snapshot().sendable()) {
      return Health.unknown().build();
    }
    MailSendStatus status = settingsService.status();
    if (status.lastAttemptFailed()) {
      return Health.down().build();
    }
    return status.lastSuccessAt() == null ? Health.unknown().build() : Health.up().build();
  }
}
