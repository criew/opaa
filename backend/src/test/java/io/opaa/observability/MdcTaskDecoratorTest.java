package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Work handed to a pool logs with the request id and indexing run of its submitter, and a pool
 * thread keeps nothing of it for the next task.
 */
class MdcTaskDecoratorTest {

  private final BeanPostProcessor executors = MdcPropagationConfiguration.mdcPropagatingExecutors();

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void anAlreadyInitialisedPoolBeanRunsItsTasksWithTheSubmittersMdcAndForgetsItAfterwards()
      throws Exception {
    ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
    pool.setCorePoolSize(1);
    pool.setMaxPoolSize(1);
    pool.initialize();
    executors.postProcessAfterInitialization(pool, "indexingTaskExecutor");
    try {
      MDC.put(RequestIds.MDC_KEY, "4bf92f3577b34da6a3ce929d0e0e4736");
      MDC.put(RequestIds.INDEXING_RUN_MDC_KEY, "run-1");
      CompletableFuture<String> submitted = new CompletableFuture<>();
      pool.execute(
          () ->
              submitted.complete(
                  MDC.get(RequestIds.MDC_KEY) + "/" + MDC.get(RequestIds.INDEXING_RUN_MDC_KEY)));
      assertThat(submitted.get(5, TimeUnit.SECONDS))
          .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736/run-1");

      MDC.clear();
      CompletableFuture<String> next = new CompletableFuture<>();
      pool.execute(() -> next.complete(String.valueOf(MDC.get(RequestIds.MDC_KEY))));
      assertThat(next.get(5, TimeUnit.SECONDS))
          .as("the same pool thread, next task")
          .isEqualTo("null");
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void aSimpleAsyncExecutorBeanCarriesTheMdcToo() throws Exception {
    SimpleAsyncTaskExecutor simple = new SimpleAsyncTaskExecutor("directory-sync-");
    executors.postProcessAfterInitialization(simple, "directorySyncExecutor");
    MDC.put(RequestIds.MDC_KEY, "4bf92f3577b34da6a3ce929d0e0e4736");
    CompletableFuture<String> submitted = new CompletableFuture<>();

    simple.execute(() -> submitted.complete(MDC.get(RequestIds.MDC_KEY)));

    assertThat(submitted.get(5, TimeUnit.SECONDS)).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
  }

  @Test
  void aTaskOfAPlainJdkPoolWrappedWithTheCallersMdcCarriesItAndLeavesTheThreadClean()
      throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      MDC.put(RequestIds.MDC_KEY, "4bf92f3577b34da6a3ce929d0e0e4736");
      CompletableFuture<String> called = new CompletableFuture<>();
      pool.execute(
          MdcTaskDecorator.withCallersMdc(() -> called.complete(MDC.get(RequestIds.MDC_KEY))));
      assertThat(called.get(5, TimeUnit.SECONDS)).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");

      MDC.clear();
      Future<String> next = pool.submit(() -> String.valueOf(MDC.get(RequestIds.MDC_KEY)));
      assertThat(next.get(5, TimeUnit.SECONDS)).isEqualTo("null");
    } finally {
      pool.shutdown();
    }
  }
}
