package io.opaa.account;

import io.opaa.instance.ScheduledRunLocks;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily run of {@link LocalTokenCleanupService} (ADR-0033, Entscheidung 7) at 03:20 server time
 * - off business hours, between the monthly audit and diagnostic retention runs. The scheduled-run
 * lock lets one instance run it per night, and every mail and audit entry of its steps stands
 * behind a marker of its own, so a second run sends and records nothing twice; the schedule only
 * decides when, the service decides what.
 */
@Component
public class LocalTokenCleanupScheduler {

  private final LocalTokenCleanupService service;

  public LocalTokenCleanupScheduler(LocalTokenCleanupService service) {
    this.service = service;
  }

  @Scheduled(cron = "0 20 3 * * *")
  @SchedulerLock(
      name = "local-account-maintenance",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_DAILY,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_NIGHTLY)
  public void runDaily() {
    service.runOnce();
  }
}
