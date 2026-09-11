package io.opaa.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Whether mail is currently working (#1536, ADR-0033 Entscheidung 10): {@code DOWN} when the last
 * attempt failed, {@code UP} when the last one succeeded, {@code UNKNOWN} while SMTP is not
 * configured or nothing has been sent yet.
 *
 * <p>State without details: a monitoring system needs to know that mail is failing, the cause
 * belongs on the settings page behind {@code SYSTEM_ADMIN}. Shown by the {@code mail} group only -
 * see {@link MailHealthGroup} for why not by the overall status.
 */
@Component(MailHealthGroup.CONTRIBUTOR)
@ConditionalOnProperty(name = "management.health.mail.enabled", matchIfMissing = true)
public class MailHealthIndicator implements HealthIndicator {

  private final MailSettingsService settingsService;

  public MailHealthIndicator(MailSettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @Override
  public Health health() {
    try {
      if (!settingsService.snapshot().sendable()) {
        return Health.unknown().build();
      }
      MailSendStatus status = settingsService.status();
      if (status.lastAttemptFailed()) {
        return Health.down().build();
      }
      return status.lastSuccessAt() == null ? Health.unknown().build() : Health.up().build();
    } catch (RuntimeException e) {
      // A missing encryption key or an unreadable row makes the snapshot itself raise; a health
      // endpoint answers that with DOWN, never with a 500.
      return Health.down().build();
    }
  }
}
