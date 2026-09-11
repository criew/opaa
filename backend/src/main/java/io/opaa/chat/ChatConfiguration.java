package io.opaa.chat;

import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.observability.ChatMetrics;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The chat package's wiring: the two dedicated thread pools of its asynchronous LLM jobs ({@link
 * ChatTitleGenerationService#generateTitleAsync}, {@link ChatNoteExtractionService#condenseAsync}),
 * the note's configuration properties and the chat metrics. Both pools are separate from {@code
 * indexingTaskExecutor} ({@code io.opaa.indexing.IndexingConfiguration}) so a burst of indexing
 * work can never starve them, and vice versa. {@code @EnableAsync} itself is already declared on
 * {@code IndexingConfiguration} - one declaration anywhere in the application context enables
 * {@code @Async} processing for all of it, so it is deliberately not repeated here.
 *
 * <p>Deliberately tiny: both are a single short LLM call, never a queue-worthy background job.
 *
 * <p><b>#561 review nit: {@code queueCapacity} of 0, not a large number.</b> {@link
 * ThreadPoolTaskExecutor} only grows past {@code corePoolSize} once its queue is full ({@link
 * java.util.concurrent.ThreadPoolExecutor}'s documented behaviour) - a sizeable queue capacity
 * would let every submission queue up behind the two core threads and {@code maxPoolSize} would
 * never actually be reached in practice, making it a dead setting. {@code 0} makes the queue a
 * direct hand-off ({@link java.util.concurrent.SynchronousQueue} under the hood): a submission
 * either gets a free thread immediately, grows the pool up to {@code maxPoolSize}, or - only once
 * even that is exhausted - is rejected. {@link #loggingRejectedExecutionHandler()} makes that
 * rejection an observable warning instead of {@link ThreadPoolExecutor.DiscardPolicy}'s silent
 * drop; {@link ThreadPoolExecutor.CallerRunsPolicy} is deliberately not used here even though it is
 * the more common choice, because the caller is the very request thread {@code @Async} exists to
 * keep this LLM call off (#557's "kein zweiter LLM-Roundtrip auf dem kritischen Pfad der Antwort")
 * - running it there on rejection would defeat that design goal exactly when the system is already
 * under load.
 */
@Configuration
@EnableConfigurationProperties(ChatNoteProperties.class)
public class ChatConfiguration {

  private static final Logger log = LoggerFactory.getLogger(ChatConfiguration.class);

  @Bean
  TaskExecutor chatTitleTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(0);
    executor.setThreadNamePrefix("chat-title-");
    executor.setRejectedExecutionHandler(loggingRejectedExecutionHandler("chat title generation"));
    executor.initialize();
    return executor;
  }

  /**
   * The pool of #1487's Gesprächsnotiz condensation ({@link
   * ChatNoteExtractionService#condenseAsync}) - separate from the title pool above, not because the
   * two jobs differ in shape but because they differ in frequency: the title runs once per chat,
   * the condensation once per <em>turn</em>, so a busy conversation would otherwise crowd out the
   * one-off job through the deliberately queue-free hand-off both use.
   */
  @Bean
  TaskExecutor chatNoteTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(0);
    executor.setThreadNamePrefix("chat-note-");
    executor.setRejectedExecutionHandler(loggingRejectedExecutionHandler("chat note condensation"));
    executor.initialize();
    return executor;
  }

  @Bean
  ChatMetrics chatMetrics(MeterRegistry meterRegistry) {
    return new ChatMetrics(meterRegistry);
  }

  private RejectedExecutionHandler loggingRejectedExecutionHandler(String job) {
    return (task, executor) ->
        log.warn(
            "{} task rejected - pool exhausted (active={}, queue={}, maxPoolSize={}); the affected"
                + " chat keeps the state it had",
            job,
            executor.getActiveCount(),
            executor.getQueue().size(),
            executor.getMaximumPoolSize());
  }
}
