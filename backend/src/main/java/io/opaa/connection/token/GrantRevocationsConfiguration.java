package io.opaa.connection.token;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The threads of {@link GrantRevocations} (ADR-0053: one pool per process; the instances share the
 * queue in the database): a revocation is a call to a provider and does not belong on the
 * application's single shared scheduler thread. On shutdown a revocation in flight gets {@link
 * #SHUTDOWN_GRACE_SECONDS} to finish; one cut off stays in the queue for any instance.
 */
@Configuration
class GrantRevocationsConfiguration {

  static final String SCHEDULER = "grantRevocationScheduler";
  static final int THREADS = 2;
  static final int SHUTDOWN_GRACE_SECONDS = 2;

  @Bean(name = SCHEDULER, destroyMethod = "shutdown")
  static TaskScheduler grantRevocationScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(THREADS);
    scheduler.setThreadNamePrefix("oauth-revocation-");
    scheduler.setDaemon(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(SHUTDOWN_GRACE_SECONDS);
    return scheduler;
  }
}
