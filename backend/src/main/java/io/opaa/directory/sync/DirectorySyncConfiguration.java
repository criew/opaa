package io.opaa.directory.sync;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * The synchronisation policy's own configuration. The productive {@link DirectoryClient} is wired
 * by {@code io.opaa.directory.sync.connector.DirectoryConnectorConfiguration}, one connector per
 * provider.
 */
@Configuration
@EnableConfigurationProperties(DirectorySyncProperties.class)
public class DirectorySyncConfiguration {

  /**
   * Carries the scheduled runs off the application's single scheduler thread: a full directory
   * fetch takes as long as the directory takes, and every other {@code @Scheduled} tick would wait
   * behind it. One thread per run and no queue: a run that finds its provider still busy is
   * rejected by {@link DirectorySyncRunLock} at once instead of running again right after.
   */
  @Bean
  TaskExecutor directorySyncExecutor() {
    return new SimpleAsyncTaskExecutor("directory-sync-");
  }
}
