package io.opaa.library;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The ticks of {@link PrivateLibraryDeletionRun}: daily after the reconciliation of the
 * connections, and every few minutes for the erasures a running run held up. Switched off with
 * {@code opaa.library.private-deletion.schedule-enabled=false}, where a tick would erase what a
 * test marked; the run itself is then called directly.
 */
@Component
@ConditionalOnProperty(
    prefix = "opaa.library.private-deletion",
    name = "schedule-enabled",
    matchIfMissing = true)
public class PrivateLibraryDeletionSchedule {

  private final PrivateLibraryDeletionRun run;

  public PrivateLibraryDeletionSchedule(PrivateLibraryDeletionRun run) {
    this.run = run;
  }

  @Scheduled(cron = "0 45 4 * * *")
  public void daily() {
    run.daily();
  }

  @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
  public void continuePending() {
    run.continuePending();
  }
}
