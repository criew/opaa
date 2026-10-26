package io.opaa.llm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** The rerank role's load limits and its own probe thread. */
@Configuration
@EnableConfigurationProperties(RerankProperties.class)
public class RerankRoleConfiguration {

  /**
   * The rerank role's own probe thread. The application's default {@link TaskScheduler} has a
   * single thread shared by the indexing schedulers, and a rerank endpoint that accepts a
   * connection but never answers would block it for connect timeout plus {@code
   * OPAA_RERANK_TIMEOUT} on every probe - stalling indexing over an optional retrieval component.
   *
   * <p>One thread is enough: the probe schedule is the only recurring task on it and runs with
   * {@code fixedDelay}, so a hanging probe delays the next probe and nothing else.
   */
  @Bean(destroyMethod = "shutdown")
  TaskScheduler rerankProbeScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("rerank-probe-");
    scheduler.setDaemon(true);
    // A probe in flight must not hold up shutdown; its result would be discarded anyway.
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    scheduler.initialize();
    return scheduler;
  }
}
