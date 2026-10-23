package io.opaa.chat;

import io.opaa.common.DatabaseClock;
import io.opaa.instance.ScheduledRunLocks;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The nightly run of {@link LockedAccountChatCleanupService} at 02:30 server time, apart from the
 * chat cleanup of the spaces at 03:50. The scheduled-run lock lets one instance run it per night
 * (ADR-0053); its cap per run keeps it far below the lock's longest hold. Now is the database
 * clock, the clock the periods of every instance are counted against.
 */
@Component
public class LockedAccountChatCleanupScheduler {

  private final LockedAccountChatCleanupService cleanupService;
  private final DatabaseClock databaseClock;

  public LockedAccountChatCleanupScheduler(
      LockedAccountChatCleanupService cleanupService, DatabaseClock databaseClock) {
    this.cleanupService = cleanupService;
    this.databaseClock = databaseClock;
  }

  /** At start, on every instance: a start with the deletion switched off forgets the count. */
  @EventListener(ApplicationReadyEvent.class)
  public void onStart() {
    cleanupService.forgetCountingWhenSwitchedOff();
  }

  @Scheduled(cron = "0 30 2 * * *")
  @SchedulerLock(
      name = "chat-locked-account-cleanup",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_DAILY,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_NIGHTLY)
  public void runDaily() {
    cleanupService.runOnce(databaseClock.now());
  }
}
