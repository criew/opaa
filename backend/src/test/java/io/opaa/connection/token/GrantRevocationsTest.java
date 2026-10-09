package io.opaa.connection.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The revocation pool runs every commit's revocations off the caller's thread as one entry, never
 * blocks the caller, drops a commit its bounded queue cannot hold with a WARN line, survives a
 * failing revocation and, when it stops, interrupts what runs and drops what waits, naming how
 * many. No test waits for time to pass: every step is released by a latch.
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

    submit(
        revocations,
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

    submit(
        revocations,
        UUID.randomUUID(),
        () -> {
          running.countDown();
          awaitQuietly(gate);
        });
    assertThat(running.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    submit(revocations, UUID.randomUUID(), queuedRan::countDown);
    // the one thread is busy and the one queue slot taken: this one returns at once, dropped
    submit(revocations, dropped, () -> {});

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

    submit(
        revocations,
        profile,
        () -> {
          throw new IllegalStateException("provider exploded");
        });
    submit(revocations, UUID.randomUUID(), survived::countDown);

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
  void stoppingInterruptsTheRunningRevocationAndDropsTheWaitingOnesWithACount() throws Exception {
    GrantRevocations revocations = new GrantRevocations(1, 10, Duration.ZERO);
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    CountDownLatch never = new CountDownLatch(1);
    CountDownLatch waitingRan = new CountDownLatch(1);

    submit(
        revocations,
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
    submit(revocations, UUID.randomUUID(), waitingRan::countDown);
    submit(revocations, UUID.randomUUID(), waitingRan::countDown);

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
    submit(revocations, late, waitingRan::countDown);
    assertThat(logs.list)
        .as("a revocation after the stop is dropped, not run on the caller's thread")
        .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains(late.toString()));
    assertThat(waitingRan.getCount()).isOne();
  }

  /** One commit is one entry, however many grants it holds: the queue bounds commits. */
  @Test
  void aCommitOfManyGrantsIsOneEntryAndRevokesEveryGrant() {
    GrantRevocations revocations = pool(1, 1);
    AtomicInteger revoked = new AtomicInteger();
    List<GrantRevocations.Grant> grants = new ArrayList<>();
    for (int i = 0; i < 1500; i++) {
      grants.add(new GrantRevocations.Grant(UUID.randomUUID(), revoked::incrementAndGet));
    }

    revocations.submit(grants);

    await().atMost(WAIT).until(revocations::idle);
    assertThat(revoked).hasValue(1500);
    assertThat(logs.list).noneSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
  }

  @Test
  void aFailingGrantDoesNotStopTheRestOfItsCommit() {
    GrantRevocations revocations = pool(1, 1);
    AtomicInteger revoked = new AtomicInteger();
    UUID failing = UUID.randomUUID();

    revocations.submit(
        List.of(
            new GrantRevocations.Grant(UUID.randomUUID(), revoked::incrementAndGet),
            new GrantRevocations.Grant(
                failing,
                () -> {
                  throw new IllegalStateException("provider exploded");
                }),
            new GrantRevocations.Grant(UUID.randomUUID(), revoked::incrementAndGet)));

    await().atMost(WAIT).until(revocations::idle);
    assertThat(revoked).hasValue(2);
    assertThat(logs.list)
        .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains(failing.toString()));
  }

  /** A stop interrupts the running grant and drops the rest of its commit with their number. */
  @Test
  void stoppingInTheMiddleOfACommitDropsItsRemainingGrants() throws Exception {
    GrantRevocations revocations = new GrantRevocations(1, 10, Duration.ZERO);
    CountDownLatch running = new CountDownLatch(1);
    AtomicInteger later = new AtomicInteger();

    revocations.submit(
        List.of(
            new GrantRevocations.Grant(
                UUID.randomUUID(),
                () -> {
                  running.countDown();
                  try {
                    new CountDownLatch(1).await();
                  } catch (InterruptedException e) {
                    // as the OAuth client does: the interrupt stays set
                    Thread.currentThread().interrupt();
                  }
                }),
            new GrantRevocations.Grant(UUID.randomUUID(), later::incrementAndGet),
            new GrantRevocations.Grant(UUID.randomUUID(), later::incrementAndGet)));
    assertThat(running.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

    revocations.shutdown();

    await().atMost(WAIT).until(revocations::idle);
    assertThat(later).hasValue(0);
    assertThat(logs.list)
        .anySatisfy(
            event ->
                assertThat(event.getFormattedMessage())
                    .contains("2 revocation(s) of a running commit"));
  }

  @Test
  void theProductionPoolIsBounded() {
    assertThat(GrantRevocations.THREADS).isBetween(1, 4);
    assertThat(GrantRevocations.QUEUE_CAPACITY).isPositive();
  }

  /** Submits one commit of a single grant. */
  private static void submit(GrantRevocations revocations, UUID profileId, Runnable revocation) {
    revocations.submit(List.of(new GrantRevocations.Grant(profileId, revocation)));
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
