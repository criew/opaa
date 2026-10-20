package io.opaa.library;

import io.opaa.common.ConditionalOnFeature;
import io.opaa.common.Feature;
import io.opaa.instance.ScheduledRunLocks;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily run of the Fremdzugangsfreigabe at 03:40 server time - off business hours, after the
 * local token cleanup. The scheduled-run lock lets one instance run it per night, and both services
 * claim each release under a row lock, so a second run sends and records nothing twice; the
 * schedule only decides when, the two services decide what.
 *
 * <p>The reminder runs before the expiry, in the same tick and deliberately in this order: a
 * release expiring today should have been announced while it still ran, not be reminded about after
 * it already stopped.
 */
@ConditionalOnFeature(Feature.EXTERNAL_ACCESS)
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
  @SchedulerLock(
      name = "library-external-access",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_DAILY,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_NIGHTLY)
  public void runDaily() {
    reminderService.runOnce();
    expiryService.runOnce();
  }
}
