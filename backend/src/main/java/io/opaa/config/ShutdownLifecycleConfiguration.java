package io.opaa.config;

import org.springframework.boot.autoconfigure.context.LifecycleProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.scheduling.concurrent.ExecutorConfigurationSupport;

/**
 * Replaces Spring Boot's own lifecycle processor so that {@code
 * spring.lifecycle.timeout-per-shutdown-phase} applies to the HTTP requests in flight only: the
 * phase every {@link ExecutorConfigurationSupport task executor and scheduler} stops in gets a
 * timeout of zero, so a running background task never holds the stop up.
 *
 * <p>This keeps the contract {@code IndexingJobRecoveryScheduler.recoverOnStartup} and {@code
 * UploadPendingRecoveryRunner} rely on: an indexing or upload task is interrupted on shutdown and
 * its row recovered on the next start, never awaited.
 */
@Configuration
public class ShutdownLifecycleConfiguration {

  @Bean(name = AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME)
  DefaultLifecycleProcessor lifecycleProcessor(LifecycleProperties properties) {
    DefaultLifecycleProcessor lifecycleProcessor = new DefaultLifecycleProcessor();
    lifecycleProcessor.setTimeoutPerShutdownPhase(
        properties.getTimeoutPerShutdownPhase().toMillis());
    lifecycleProcessor.setTimeoutForShutdownPhase(ExecutorConfigurationSupport.DEFAULT_PHASE, 0);
    return lifecycleProcessor;
  }
}
