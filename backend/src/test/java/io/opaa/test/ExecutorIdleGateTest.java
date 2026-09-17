package io.opaa.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ExecutorIdleGateTest {

  private static final Duration TIMEOUT = Duration.ofMillis(300);

  private final CountDownLatch started = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);
  private ThreadPoolTaskExecutor executor;

  @BeforeEach
  void setUp() {
    executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("stuck-work-");
    executor.setCorePoolSize(1);
    executor.initialize();
  }

  @AfterEach
  void tearDown() {
    release.countDown();
    executor.shutdown();
  }

  @Test
  void passesAtOnceWhenEveryExecutorIsIdle() {
    ExecutorIdleGate gate = new ExecutorIdleGate(Map.of("idleExecutor", executor), TIMEOUT);

    long waitStarted = System.nanoTime();
    assertThatCode(gate::awaitIdle).doesNotThrowAnyException();
    assertThat(Duration.ofNanos(System.nanoTime() - waitStarted)).isLessThan(TIMEOUT);
  }

  @Test
  void afterOneTimeoutEveryFurtherWaitFailsWithoutWaitingAgain() throws InterruptedException {
    executor.execute(this::blockUntilReleased);
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    ExecutorIdleGate gate = new ExecutorIdleGate(Map.of("stuckExecutor", executor), TIMEOUT);

    long firstStarted = System.nanoTime();
    assertThatThrownBy(gate::awaitIdle)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("did not become idle within")
        .hasMessageContaining("stuckExecutor (active=1")
        .hasMessageContaining("thread stuck-work-1")
        .hasMessageContaining("blockUntilReleased");
    assertThat(Duration.ofNanos(System.nanoTime() - firstStarted)).isGreaterThanOrEqualTo(TIMEOUT);

    long secondStarted = System.nanoTime();
    assertThatThrownBy(gate::awaitIdle)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not waiting again")
        .hasMessageContaining("stuckExecutor (active=1");
    assertThat(Duration.ofNanos(System.nanoTime() - secondStarted)).isLessThan(TIMEOUT);
  }

  @Test
  void aClosedGateOpensAgainOnceTheSlowTaskHasFinished() throws InterruptedException {
    executor.execute(this::blockUntilReleased);
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    ExecutorIdleGate gate = new ExecutorIdleGate(Map.of("slowExecutor", executor), TIMEOUT);
    assertThatThrownBy(gate::awaitIdle).hasMessageContaining("did not become idle within");

    release.countDown();
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (executor.getActiveCount() > 0 && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(executor.getActiveCount()).isZero();

    long waitStarted = System.nanoTime();
    assertThatCode(gate::awaitIdle).doesNotThrowAnyException();
    assertThatCode(gate::awaitIdle).doesNotThrowAnyException();
    assertThat(Duration.ofNanos(System.nanoTime() - waitStarted)).isLessThan(TIMEOUT);
  }

  private void blockUntilReleased() {
    started.countDown();
    try {
      release.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
