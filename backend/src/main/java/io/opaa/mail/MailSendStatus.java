package io.opaa.mail;

import java.time.Instant;

/**
 * When mail last worked and when it last did not (#1536, ADR-0033 Entscheidung 10). Held in memory
 * by {@link MailSettingsService} alongside the stored row so {@link MailHealthIndicator} answers
 * without a query per scrape; the durable copy in {@code mail_settings} is what survives a restart.
 *
 * @param lastSuccessAt when a message was last handed to the SMTP server, {@code null} if never
 * @param lastFailureAt when an attempt last failed, {@code null} if never
 * @param lastFailureReason the credential-free cause of that failure
 */
public record MailSendStatus(
    Instant lastSuccessAt, Instant lastFailureAt, String lastFailureReason) {

  static final MailSendStatus NEVER_ATTEMPTED = new MailSendStatus(null, null, null);

  /**
   * Whether the last attempt failed: there is a failure, and it is not older than the last success.
   * "Failing right now" is the only state worth taking to a monitoring system - an old failure
   * followed by a success is history, not a fault.
   */
  public boolean lastAttemptFailed() {
    if (lastFailureAt == null) {
      return false;
    }
    return lastSuccessAt == null || lastFailureAt.isAfter(lastSuccessAt);
  }
}
