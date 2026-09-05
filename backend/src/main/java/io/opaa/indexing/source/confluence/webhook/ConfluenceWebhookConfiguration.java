package io.opaa.indexing.source.confluence.webhook;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** The one-thread timer behind the webhook intake's debounce. */
@Configuration
public class ConfluenceWebhookConfiguration {

  /**
   * A dedicated scheduler rather than the shared one: the drain only starts a job and hands the
   * work to {@code indexingTaskExecutor}, so one thread is enough, and a pending batch must not
   * compete with the schedule sweep or the rerank probe for a slot.
   */
  @Bean(destroyMethod = "shutdown")
  TaskScheduler confluenceWebhookScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("confluence-webhook-");
    scheduler.setDaemon(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    scheduler.initialize();
    return scheduler;
  }
}
