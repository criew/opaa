package io.opaa.mail;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The thread of {@link MailOutboxWorker}: SMTP conversations do not belong on the application's
 * single shared scheduler thread. One thread, so a process never delivers two rows at once; on
 * shutdown a delivery in flight gets {@link #SHUTDOWN_GRACE_SECONDS} to finish, nothing new starts.
 */
@Configuration
class MailOutboxConfiguration {

  static final String SCHEDULER = "mailOutboxScheduler";
  static final int SHUTDOWN_GRACE_SECONDS = 20;

  @Bean(name = SCHEDULER, destroyMethod = "shutdown")
  static TaskScheduler mailOutboxScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("mail-outbox-");
    scheduler.setDaemon(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(SHUTDOWN_GRACE_SECONDS);
    return scheduler;
  }
}
