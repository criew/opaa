package io.opaa.llm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The operation's switch and limits of text recognition ({@link TextRecognitionProperties}) and the
 * role's own probe thread: a service that accepts a connection but never answers must not block the
 * application's shared scheduler.
 */
@Configuration
@EnableConfigurationProperties(TextRecognitionProperties.class)
public class TextRecognitionRoleConfiguration {

  @Bean(destroyMethod = "shutdown")
  TaskScheduler textRecognitionProbeScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("text-recognition-probe-");
    scheduler.setDaemon(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    scheduler.initialize();
    return scheduler;
  }
}
