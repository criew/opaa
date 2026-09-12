package io.opaa.mail;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The mail dispatch thread runs sends off the caller's thread in order, drops what its bounded
 * queue cannot hold with a WARN line, and stops with the context.
 */
class MailDispatchExecutorTest {

  @Test
  void sendsRunOffTheCallersThreadInOrderAndAnOverfullQueueDropsWithAWarning() throws Exception {
    MailDispatchExecutor executor = new MailDispatchExecutor();
    Logger logger = (Logger) LoggerFactory.getLogger(MailDispatchExecutor.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
    try {
      CountDownLatch gate = new CountDownLatch(1);
      CountDownLatch blocked = new CountDownLatch(1);
      AtomicInteger order = new AtomicInteger();
      String caller = Thread.currentThread().getName();
      StringBuilder threadName = new StringBuilder();
      executor.execute(
          () -> {
            threadName.append(Thread.currentThread().getName());
            blocked.countDown();
            await(gate);
          });
      assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
      // the queue holds QUEUE_CAPACITY more; one beyond that is dropped and logged
      for (int i = 0; i < MailDispatchExecutor.QUEUE_CAPACITY; i++) {
        executor.execute(order::incrementAndGet);
      }
      assertThat(executor.pending()).isEqualTo(MailDispatchExecutor.QUEUE_CAPACITY);
      executor.execute(order::incrementAndGet);
      assertThat(logs.list)
          .anySatisfy(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("dropped");
              });
      gate.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (order.get() < MailDispatchExecutor.QUEUE_CAPACITY && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertThat(order.get()).isEqualTo(MailDispatchExecutor.QUEUE_CAPACITY);
      assertThat(threadName.toString()).isEqualTo("mail-dispatch").isNotEqualTo(caller);
    } finally {
      logger.detachAppender(logs);
      executor.shutdown();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
