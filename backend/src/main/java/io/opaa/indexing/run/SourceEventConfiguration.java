package io.opaa.indexing.run;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** The one thread on which the event intake drains the due batches of this instance. */
@Configuration
@EnableConfigurationProperties(SourceEventProperties.class)
public class SourceEventConfiguration {

  /**
   * A dedicated scheduler rather than the shared one: the drain only starts a job and hands the
   * work to {@code indexingTaskExecutor}, so one thread is enough for every connector together, and
   * a due batch must not compete with the schedule sweep or the rerank probe for a slot.
   */
  @Bean(destroyMethod = "shutdown")
  TaskScheduler sourceEventScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("source-events-");
    scheduler.setDaemon(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    scheduler.initialize();
    return scheduler;
  }
}
