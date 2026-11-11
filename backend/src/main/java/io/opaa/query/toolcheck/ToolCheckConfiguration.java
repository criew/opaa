package io.opaa.query.toolcheck;

import io.opaa.common.ShutdownLifecycleConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The pool that runs tool checks: one thread, no queue (ADR-0053). The database admits one running
 * check per installation, so a second task only arrives while a given-up check still winds down
 * here; it is rejected and the starter ends its row as failed. A check interrupted by a shutdown
 * stops beating and is given up as aborted by the next start (registered in {@code
 * backend/docs/prozesslokaler-zustand.md}).
 */
@Configuration
class ToolCheckConfiguration {

  @Bean
  ThreadPoolTaskExecutor toolCheckExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(0);
    executor.setThreadNamePrefix("tool-check-");
    executor.setPhase(ShutdownLifecycleConfiguration.UNRECOVERABLE_BACKGROUND_PHASE);
    executor.initialize();
    return executor;
  }
}
