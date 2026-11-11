package io.opaa.asset;

import io.opaa.instance.ScheduledRunLocks;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link ResponsibilityLockRun} twenty minutes past every hour, on one instance at a time
 * (ADR-0053, Entscheidung 7): the period without a responsible unit starts at most an hour late and
 * the lock follows within the hour after it ended. Separate from the run so a test can drive one
 * pass without Spring's scheduling machinery.
 */
@Component
public class ResponsibilityLockScheduler {

  private final ResponsibilityLockRun run;

  ResponsibilityLockScheduler(ResponsibilityLockRun run) {
    this.run = run;
  }

  @Scheduled(cron = "0 20 * * * *")
  @SchedulerLock(
      name = "asset-responsibility-lock",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_HOURLY,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_SHORT)
  public void lockObjectsWithoutResponsibleUnit() {
    run.runOnce();
  }
}
