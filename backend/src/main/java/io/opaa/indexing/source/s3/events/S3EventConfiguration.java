package io.opaa.indexing.source.s3.events;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** The one-thread timer behind the event intake's debounce (mirrors the Confluence webhook's). */
@Configuration
public class S3EventConfiguration {

  /**
   * A dedicated scheduler rather than the shared one: the drain only starts a job and hands the
   * work to {@code indexingTaskExecutor}, so one thread is enough, and a pending batch must not
   * compete with the schedule sweep for a slot.
   */
  @Bean(destroyMethod = "shutdown")
  TaskScheduler s3EventScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("s3-events-");
    scheduler.setDaemon(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    scheduler.initialize();
    return scheduler;
  }
}
