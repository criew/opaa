package io.opaa.query.toolcheck;

import io.opaa.common.ShutdownLifecycleConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The pool that runs tool checks: one thread, no queue (ADR-0053). The database admits one running
 * check per installation, so a second task never waits here; a check interrupted by a shutdown
 * stops beating and is given up as aborted by the next start (registered in {@code
 * backend/docs/prozesslokaler-zustand.md}).
 */
@Configuration
class ToolCheckConfiguration {

  private static final Logger log = LoggerFactory.getLogger(ToolCheckConfiguration.class);

  @Bean
  ThreadPoolTaskExecutor toolCheckExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(0);
    executor.setThreadNamePrefix("tool-check-");
    executor.setPhase(ShutdownLifecycleConfiguration.UNRECOVERABLE_BACKGROUND_PHASE);
    executor.setRejectedExecutionHandler(
        (task, pool) -> log.warn("A tool check was rejected: its pool is busy"));
    executor.initialize();
    return executor;
  }
}
