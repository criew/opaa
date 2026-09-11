package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.observability.ChatMetrics;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The one outcome of the Gesprächsnotiz condensation that never reaches {@link
 * ChatNoteExtractionService} at all (#1487): a task the pool refuses because it is exhausted. It
 * has to be counted, not only logged - the condensation runs once per <em>turn</em>, so a burst of
 * concurrent turns is exactly when the pool overflows, and an uncounted rejection would leave
 * {@code opaa.chat.note.extraction} cleanest precisely under load.
 *
 * <p>Deliberately Spring-free: the executor bean is built directly, so no context of the suite is
 * touched and the pool can be driven into saturation deterministically.
 */
class ChatNoteTaskExecutorRejectionTest {

  @Test
  void aRejectedCondensationIsCountedAndNotSilentlyDropped() throws Exception {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ChatMetrics metrics = new ChatMetrics(meterRegistry);
    TaskExecutor executor = new ChatConfiguration().chatNoteTaskExecutor(metrics);
    int maxPoolSize = ((ThreadPoolTaskExecutor) executor).getMaxPoolSize();

    CountDownLatch occupied = new CountDownLatch(maxPoolSize);
    CountDownLatch release = new CountDownLatch(1);
    try {
      for (int i = 0; i < maxPoolSize; i++) {
        executor.execute(
            () -> {
              occupied.countDown();
              try {
                release.await(10, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
      }
      assertThat(occupied.await(10, TimeUnit.SECONDS))
          .as("every thread of the pool must be busy before the rejection is provoked")
          .isTrue();

      // Queue capacity is 0 (a direct hand-off), so with every thread occupied this one is refused
      // rather than queued - and the handler must not let that pass unrecorded.
      executor.execute(() -> {});

      assertThat(
              Search.in(meterRegistry)
                  .name("opaa.chat.note.extraction")
                  .tag("reason", "rejected")
                  .counter()
                  .count())
          .isEqualTo(1);
    } finally {
      release.countDown();
      ((ThreadPoolTaskExecutor) executor).shutdown();
    }
  }
}
