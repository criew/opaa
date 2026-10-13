package io.opaa.llm;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The transcription role's own probe thread: an endpoint that accepts a connection but never
 * answers must not block the application's shared scheduler, which the indexing jobs use.
 */
@Configuration
public class TranscriptionRoleConfiguration {

  @Bean(destroyMethod = "shutdown")
  TaskScheduler transcriptionProbeScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("transcription-probe-");
    scheduler.setDaemon(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    scheduler.initialize();
    return scheduler;
  }
}
