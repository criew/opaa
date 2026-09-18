package io.opaa.library;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily run of the Fremdzugangsfreigabe (#1731) at 03:40 server time - off business hours,
 * after the local token cleanup. A single instance runs it (ADR-0021), so no distributed lock is
 * needed; the schedule only decides when, the two services decide what.
 *
 * <p>The reminder runs before the expiry, in the same tick and deliberately in this order: a
 * release expiring today should have been announced while it still ran, not be reminded about after
 * it already stopped.
 */
@Component
public class LibraryExternalAccessScheduler {

  private final LibraryExternalAccessReminderService reminderService;
  private final LibraryExternalAccessExpiryService expiryService;

  public LibraryExternalAccessScheduler(
      LibraryExternalAccessReminderService reminderService,
      LibraryExternalAccessExpiryService expiryService) {
    this.reminderService = reminderService;
    this.expiryService = expiryService;
  }

  @Scheduled(cron = "0 40 3 * * *")
  public void runDaily() {
    reminderService.runOnce();
    expiryService.runOnce();
  }
}
