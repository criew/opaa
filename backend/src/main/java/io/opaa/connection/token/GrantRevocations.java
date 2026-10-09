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
 * thread that discarded them. One entry is one commit: it revokes its grants one after the other,
 * each bounded by its own call's time limit ({@code OAuthClient}), and a failing grant does not
 * stop the rest. The queue bounds commits, not grants, so an emergency shutdown of thousands of
 * grants is one entry; its memory is the snapshot the discard already held (two tokens and a
 * registration, a few KiB per grant), and {@link #QUEUE_CAPACITY} commits of single grants stay
 * within a few MiB. A commit that finds the queue full is dropped with a WARN line and the caller
 * goes on. The queue lives in this process (ADR-0021): on shutdown what runs is interrupted after a
 * short grace period and what waits is dropped, with the number of grants logged.
 */
@Component
public class GrantRevocations {

  static final int THREADS = 2;
  static final int QUEUE_CAPACITY = 1000;
  static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(2);

  private static final Logger log = LoggerFactory.getLogger(GrantRevocations.class);

  /** One grant to revoke: what revokes it, and the profile it was issued under, for the log. */
  public record Grant(UUID profileId, Runnable revocation) {}

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
            (runnable, pool) -> dropped((Commit) runnable, pool));
    this.shutdownGrace = shutdownGrace;
  }

  /**
   * Hands the revocations of one commit to the pool as one entry and returns at once; a full queue
   * or a stopped pool drops it with a WARN line. A revocation must not throw; if it does, the
   * failure is logged and the next grant of the commit is revoked.
   */
  public void submit(List<Grant> grants) {
    if (grants.isEmpty()) {
      return;
    }
    outstanding.incrementAndGet();
    executor.execute(new Commit(List.copyOf(grants)));
  }

  /** Whether no commit is waiting or running - for tests and diagnostics. */
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
    int grants = waiting.stream().mapToInt(commit -> ((Commit) commit).grants.size()).sum();
    if (grants > 0) {
      log.warn(
          "OAuth revocation pool stopped: {} revocation(s) still waiting were dropped; those"
              + " grants stay valid at their provider",
          grants);
    }
  }

  private void dropped(Commit commit, ThreadPoolExecutor pool) {
    outstanding.decrementAndGet();
    log.warn(
        "{} OAuth revocation(s) under profile(s) {} dropped ({}); those grants stay valid at their"
            + " provider",
        commit.grants.size(),
        commit.grants.stream().map(Grant::profileId).distinct().toList(),
        pool.isShutdown()
            ? "the pool is stopped"
            : "queue full, " + pool.getQueue().size() + " commits waiting");
  }

  private final class Commit implements Runnable {

    private final List<Grant> grants;

    Commit(List<Grant> grants) {
      this.grants = grants;
    }

    @Override
    public void run() {
      try {
        for (int i = 0; i < grants.size(); i++) {
          if (Thread.currentThread().isInterrupted()) {
            log.warn(
                "OAuth revocation pool stopped: {} revocation(s) of a running commit were"
                    + " dropped; those grants stay valid at their provider",
                grants.size() - i);
            return;
          }
          revoke(grants.get(i));
        }
      } finally {
        outstanding.decrementAndGet();
      }
    }

    private void revoke(Grant grant) {
      try {
        grant.revocation().run();
      } catch (RuntimeException e) {
        log.warn(
            "OAuth revocation for a grant of profile {} failed ({})",
            grant.profileId(),
            e.getClass().getSimpleName());
      }
    }
  }
}
