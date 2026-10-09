package io.opaa.connection.token;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The bounded pool on which discarded OAuth grants are revoked at their provider, never on the
 * thread that discarded them: a fixed number of threads and a bounded queue. A revocation that
 * finds the queue full is dropped with a WARN line and the caller goes on; a lost revocation costs
 * no access to OPAA, whose token is already gone, only leaves the grant valid at the provider until
 * it expires. Each revocation is bounded by its own call's time limit ({@code OAuthClient}). The
 * queue lives in this process (ADR-0021): on shutdown what runs is interrupted after a short grace
 * period and what waits is dropped, with its count logged.
 */
@Component
public class GrantRevocations {

  static final int THREADS = 2;
  static final int QUEUE_CAPACITY = 1000;
  static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(2);

  private static final Logger log = LoggerFactory.getLogger(GrantRevocations.class);

  private final ThreadPoolExecutor executor;
  private final Duration shutdownGrace;
  private final AtomicInteger outstanding = new AtomicInteger();

  GrantRevocations() {
    this(THREADS, QUEUE_CAPACITY, SHUTDOWN_GRACE);
  }

  GrantRevocations(int threads, int queueCapacity, Duration shutdownGrace) {
    AtomicInteger numbers = new AtomicInteger();
    this.executor =
        new ThreadPoolExecutor(
            threads,
            threads,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            runnable -> {
              Thread thread = new Thread(runnable, "oauth-revocation-" + numbers.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            },
            (runnable, pool) -> dropped((Revocation) runnable, pool));
    this.shutdownGrace = shutdownGrace;
  }

  /**
   * Hands the revocation of a grant of {@code profileId} to the pool and returns at once; a full
   * queue or a stopped pool drops it with a WARN line. {@code revocation} must not throw; if it
   * does, the failure is logged and the pool goes on.
   */
  public void submit(UUID profileId, Runnable revocation) {
    outstanding.incrementAndGet();
    executor.execute(new Revocation(profileId, revocation));
  }

  /** Whether no revocation is waiting or running - for tests and diagnostics. */
  public boolean idle() {
    return outstanding.get() == 0;
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
    try {
      if (executor.awaitTermination(shutdownGrace.toMillis(), TimeUnit.MILLISECONDS)) {
        return;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    List<Runnable> waiting = executor.shutdownNow();
    outstanding.addAndGet(-waiting.size());
    if (!waiting.isEmpty()) {
      log.warn(
          "OAuth revocation pool stopped: {} revocation(s) still waiting were dropped; those"
              + " grants stay valid at their provider until they expire",
          waiting.size());
    }
  }

  private void dropped(Revocation revocation, ThreadPoolExecutor pool) {
    outstanding.decrementAndGet();
    log.warn(
        "OAuth revocation for a grant of profile {} dropped ({}); the grant stays valid at its"
            + " provider until it expires",
        revocation.profileId,
        pool.isShutdown()
            ? "the pool is stopped"
            : "queue full, " + pool.getQueue().size() + " waiting");
  }

  private final class Revocation implements Runnable {

    private final UUID profileId;
    private final Runnable work;

    Revocation(UUID profileId, Runnable work) {
      this.profileId = profileId;
      this.work = work;
    }

    @Override
    public void run() {
      try {
        work.run();
      } catch (RuntimeException e) {
        log.warn(
            "OAuth revocation for a grant of profile {} failed ({})",
            profileId,
            e.getClass().getSimpleName());
      } finally {
        outstanding.decrementAndGet();
      }
    }
  }
}
