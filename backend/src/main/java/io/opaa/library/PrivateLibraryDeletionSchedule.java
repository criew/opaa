package io.opaa.library;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * The ticks of {@link PrivateLibraryDeletionRun}: daily after the reconciliation of the
 * connections, and every few minutes for the erasures a running run held up. Switched off with
 * {@code opaa.library.private-deletion.schedule-enabled=false}, where a tick would erase what a
 * test marked; the run itself is then called directly.
 *
 * <p>Both ticks run on {@link #privateLibraryDeletionScheduler()}, not on the application's single
 * shared scheduler thread: an erasure removes every stored original of a library over the object
 * store, one call each, and a mass deactivation can queue up many libraries. One thread is enough;
 * the two ticks never overlap.
 */
@Component
@ConditionalOnProperty(
    prefix = "opaa.library.private-deletion",
    name = "schedule-enabled",
    matchIfMissing = true)
public class PrivateLibraryDeletionSchedule {

  static final String SCHEDULER = "privateLibraryDeletionScheduler";

  private final PrivateLibraryDeletionRun run;

  public PrivateLibraryDeletionSchedule(PrivateLibraryDeletionRun run) {
    this.run = run;
  }

  @Bean(name = SCHEDULER, destroyMethod = "shutdown")
  static TaskScheduler privateLibraryDeletionScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("private-library-deletion-");
    scheduler.setDaemon(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    return scheduler;
  }

  @Scheduled(cron = "0 45 4 * * *", scheduler = SCHEDULER)
  public void daily() {
    run.daily();
  }

  @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M", scheduler = SCHEDULER)
  public void continuePending() {
    run.continuePending();
  }
}
