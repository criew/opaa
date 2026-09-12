package io.opaa.mail;

/**
 * The package-private test hooks of the mail subsystem for tests outside {@code io.opaa.mail}: the
 * local-account tests configure SMTP against GreenMail and must leave the shared snapshot and
 * send-status caches as they found them.
 */
public final class MailTestSupport {

  private MailTestSupport() {}

  public static void resetCaches(MailSettingsService service) {
    service.resetCaches();
  }
}
