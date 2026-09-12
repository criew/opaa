package io.opaa.auth.local;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily run of {@link LocalTokenCleanupService} (ADR-0033, Entscheidung 7) at 03:20 server time
 * - off business hours, between the monthly audit and diagnostic retention runs. A single instance
 * runs it (ADR-0021), so no distributed lock is needed; the schedule only decides when, the service
 * decides what.
 */
@Component
public class LocalTokenCleanupScheduler {

  private final LocalTokenCleanupService service;

  public LocalTokenCleanupScheduler(LocalTokenCleanupService service) {
    this.service = service;
  }

  @Scheduled(cron = "0 20 3 * * *")
  public void runDaily() {
    service.runOnce();
  }
}
