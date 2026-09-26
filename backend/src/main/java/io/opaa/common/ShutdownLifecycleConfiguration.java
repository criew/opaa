package io.opaa.common;

import java.time.Duration;
import org.springframework.boot.autoconfigure.context.LifecycleProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.scheduling.concurrent.ExecutorConfigurationSupport;

/**
 * Replaces Spring Boot's own lifecycle processor so that {@code
 * spring.lifecycle.timeout-per-shutdown-phase} applies to the HTTP requests in flight only. Every
 * {@link ExecutorConfigurationSupport task executor and scheduler} stops in a phase after the web
 * server, and that phase's stop waits for the tasks currently running - with the configured window
 * it would be an indexing run, not a request, that holds a stop up. Two windows instead:
 *
 * <ul>
 *   <li>{@link ExecutorConfigurationSupport#DEFAULT_PHASE}: zero. An indexing or upload task is
 *       interrupted at once and its row recovered on the next start ({@code
 *       IndexingJobRecoveryScheduler.recoverOnStartup}, {@code UploadPendingRecoveryRunner}).
 *   <li>{@link #UNRECOVERABLE_BACKGROUND_PHASE}: a short window. What runs there has no recovery at
 *       all, so an interrupt loses the result for good.
 * </ul>
 *
 * <p>Note that the property is a window <em>per phase</em>, not a budget for the whole shutdown: a
 * stop can take the configured window plus {@link #UNRECOVERABLE_BACKGROUND_WINDOW}.
 */
@Configuration
public class ShutdownLifecycleConfiguration {

  /**
   * The lifecycle phase of background pools whose task is lost for good when it is interrupted -
   * the chat title and the chat note condensation, both user-visible and never retried. Above
   * {@link ExecutorConfigurationSupport#DEFAULT_PHASE}, so these pools stop first and their window
   * is not shared with anything that recovers.
   */
  public static final int UNRECOVERABLE_BACKGROUND_PHASE =
      ExecutorConfigurationSupport.DEFAULT_PHASE + 1;

  /**
   * Long enough for a model call that is already answering to land, short enough to be unnoticeable
   * in a deployment. It is a grace period, not a guarantee: a slower call is still interrupted.
   */
  static final Duration UNRECOVERABLE_BACKGROUND_WINDOW = Duration.ofSeconds(2);

  @Bean(name = AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME)
  DefaultLifecycleProcessor lifecycleProcessor(LifecycleProperties properties) {
    DefaultLifecycleProcessor lifecycleProcessor = new DefaultLifecycleProcessor();
    lifecycleProcessor.setTimeoutPerShutdownPhase(
        properties.getTimeoutPerShutdownPhase().toMillis());
    lifecycleProcessor.setTimeoutForShutdownPhase(ExecutorConfigurationSupport.DEFAULT_PHASE, 0);
    lifecycleProcessor.setTimeoutForShutdownPhase(
        UNRECOVERABLE_BACKGROUND_PHASE, UNRECOVERABLE_BACKGROUND_WINDOW.toMillis());
    return lifecycleProcessor;
  }
}
