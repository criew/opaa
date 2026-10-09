package io.opaa.connection.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The revocation pool runs every revocation off the caller's thread, never blocks the caller, drops
 * what its bounded queue cannot hold with a WARN line, survives a failing revocation and, when it
 * stops, interrupts what runs and drops what waits, naming how many. No test waits for time to
 * pass: every step is released by a latch.
 */
class GrantRevocationsTest {

  private static final Duration WAIT = Duration.ofSeconds(10);

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(GrantRevocations.class);
  private final List<GrantRevocations> pools = new CopyOnWriteArrayList<>();

  @BeforeEach
  void captureLogs() {
    logs.list = new CopyOnWriteArrayList<>();
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void stop() {
    logger.detachAppender(logs);
    pools.forEach(GrantRevocations::shutdown);
  }

  @Test
  void aRevocationRunsOffTheCallersThread() throws Exception {
    GrantRevocations revocations = pool(1, 1);
    CountDownLatch ran = new CountDownLatch(1);
    String caller = Thread.currentThread().getName();
    StringBuilder runner = new StringBuilder();

    revocations.submit(
        UUID.randomUUID(),
        () -> {
          runner.append(Thread.currentThread().getName());
          ran.countDown();
        });

    assertThat(ran.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    await().atMost(WAIT).until(revocations::idle);
    assertThat(runner.toString()).startsWith("oauth-revocation-").isNotEqualTo(caller);
  }

  @Test
  void aFullQueueDropsWithAWarningAndNeverBlocksTheCaller() throws Exception {
    GrantRevocations revocations = pool(1, 1);
    CountDownLatch gate = new CountDownLatch(1);
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch queuedRan = new CountDownLatch(1);
    UUID dropped = UUID.randomUUID();

    revocations.submit(
        UUID.randomUUID(),
        () -> {
          running.countDown();
          awaitQuietly(gate);
        });
    assertThat(running.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    revocations.submit(UUID.randomUUID(), queuedRan::countDown);
    // the one thread is busy and the one queue slot taken: this one returns at once, dropped
    revocations.submit(dropped, () -> {});

    assertThat(revocations.idle()).isFalse();
    assertThat(logs.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .contains("dropped")
                  .contains(dropped.toString());
            });
    gate.countDown();
    assertThat(queuedRan.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    await().atMost(WAIT).until(revocations::idle);
  }

  @Test
  void aFailingRevocationIsLoggedAndTheNextOneStillRuns() throws Exception {
    GrantRevocations revocations = pool(1, 10);
    CountDownLatch survived = new CountDownLatch(1);
    UUID profile = UUID.randomUUID();

    revocations.submit(
        profile,
        () -> {
          throw new IllegalStateException("provider exploded");
        });
    revocations.submit(UUID.randomUUID(), survived::countDown);

    assertThat(survived.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    await().atMost(WAIT).until(revocations::idle);
    assertThat(logs.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .contains(profile.toString())
                  .contains("IllegalStateException");
            });
  }

  @Test
  void stoppingInterruptsTheRunningRevocationAndDropsTheWaitingOnesWithACount()
      throws Exception {
    GrantRevocations revocations = new GrantRevocations(1, 10, Duration.ZERO);
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    CountDownLatch never = new CountDownLatch(1);
    CountDownLatch waitingRan = new CountDownLatch(1);

    revocations.submit(
        UUID.randomUUID(),
        () -> {
          running.countDown();
          try {
            never.await();
          } catch (InterruptedException e) {
            interrupted.countDown();
          }
        });
    assertThat(running.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    revocations.submit(UUID.randomUUID(), waitingRan::countDown);
    revocations.submit(UUID.randomUUID(), waitingRan::countDown);

    revocations.shutdown();

    assertThat(interrupted.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    assertThat(waitingRan.getCount()).isOne();
    assertThat(logs.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("2 revocation(s)");
            });

    UUID late = UUID.randomUUID();
    revocations.submit(late, waitingRan::countDown);
    assertThat(logs.list)
        .as("a revocation after the stop is dropped, not run on the caller's thread")
        .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains(late.toString()));
    assertThat(waitingRan.getCount()).isOne();
  }

  @Test
  void theProductionPoolIsBounded() {
    assertThat(GrantRevocations.THREADS).isBetween(1, 4);
    assertThat(GrantRevocations.QUEUE_CAPACITY).isPositive();
  }

  private GrantRevocations pool(int threads, int capacity) {
    GrantRevocations revocations = new GrantRevocations(threads, capacity, Duration.ZERO);
    pools.add(revocations);
    return revocations;
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
