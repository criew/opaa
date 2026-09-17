package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.config.ShutdownLifecycleConfiguration;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.LifecycleProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.core.task.TaskExecutor;

/**
 * The shutdown window is for the HTTP requests in flight, not for background runs (#1710): a
 * running indexing task must be interrupted at once - {@code IndexingJobRecoveryScheduler} recovers
 * its row on the next start - instead of holding the stop up until the window expires.
 *
 * <p>The window used here is two seconds; the production default is a minute, which would make the
 * same wait a minute long.
 */
class IndexingExecutorShutdownTest {

  private static final Duration WINDOW = Duration.ofSeconds(2);

  @Test
  void aRunningIndexingTaskDoesNotHoldUpTheStop() throws Exception {
    try (AnnotationConfigApplicationContext context =
        contextWith(ShutdownLifecycleConfiguration.class)) {
      BlockingTask task = startTaskOn(context);

      long elapsedMillis = closeAndMeasure(context);

      assertThat(elapsedMillis).isLessThan(WINDOW.toMillis() / 2);
      assertThat(task.interrupted()).isTrue();
    }
  }

  @Test
  void withoutThePinnedPhaseTheSameTaskHoldsTheStopUpToTheWindow() throws Exception {
    try (AnnotationConfigApplicationContext context = contextWith(StockLifecycleProcessor.class)) {
      BlockingTask task = startTaskOn(context);

      long elapsedMillis = closeAndMeasure(context);

      assertThat(elapsedMillis).isGreaterThanOrEqualTo(WINDOW.toMillis() * 3 / 4);
      assertThat(task.interrupted()).isTrue();
    }
  }

  private static AnnotationConfigApplicationContext contextWith(Class<?> lifecycleConfiguration) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.register(IndexingExecutor.class, lifecycleConfiguration);
    context.refresh();
    return context;
  }

  private static BlockingTask startTaskOn(AnnotationConfigApplicationContext context)
      throws InterruptedException {
    BlockingTask task = new BlockingTask();
    context.getBean(TaskExecutor.class).execute(task);
    assertThat(task.awaitStart()).isTrue();
    return task;
  }

  private static long closeAndMeasure(AnnotationConfigApplicationContext context) {
    long startedAt = System.nanoTime();
    context.close();
    return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
  }

  /** The production {@code indexingTaskExecutor} bean, wired as it is in the application. */
  @Configuration
  static class IndexingExecutor {

    @Bean
    TaskExecutor indexingTaskExecutor() {
      return new IndexingConfiguration()
          .indexingTaskExecutor(
              new IndexingProperties(
                  0, 0, 0, new IndexingProperties.ThreadPool(1, 1, 10), null, null, null, 0));
    }

    @Bean
    LifecycleProperties lifecycleProperties() {
      LifecycleProperties properties = new LifecycleProperties();
      properties.setTimeoutPerShutdownPhase(WINDOW);
      return properties;
    }
  }

  /** Spring Boot's own wiring, which lets the window apply to the executor phase as well. */
  @Configuration
  static class StockLifecycleProcessor {

    @Bean(name = AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME)
    DefaultLifecycleProcessor lifecycleProcessor(LifecycleProperties properties) {
      DefaultLifecycleProcessor lifecycleProcessor = new DefaultLifecycleProcessor();
      lifecycleProcessor.setTimeoutPerShutdownPhase(
          properties.getTimeoutPerShutdownPhase().toMillis());
      return lifecycleProcessor;
    }
  }

  private static final class BlockingTask implements Runnable {

    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicBoolean interrupted = new AtomicBoolean();

    @Override
    public void run() {
      started.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        interrupted.set(true);
        Thread.currentThread().interrupt();
      }
    }

    boolean awaitStart() throws InterruptedException {
      return started.await(5, TimeUnit.SECONDS);
    }

    boolean interrupted() throws InterruptedException {
      // The interrupt reaches the task's own thread while the context is already closing.
      for (int attempt = 0; attempt < 50 && !interrupted.get(); attempt++) {
        TimeUnit.MILLISECONDS.sleep(20);
      }
      return interrupted.get();
    }
  }
}
