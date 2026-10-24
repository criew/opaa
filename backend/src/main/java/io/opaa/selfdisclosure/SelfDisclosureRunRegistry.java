package io.opaa.selfdisclosure;

import io.opaa.selfdisclosure.SelfDisclosureAbort.AbortCause;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The exports this instance streams right now (ADR-0053, Entscheidung 12: a bound per instance,
 * kept in memory on purpose). A slot is taken before the ticket is redeemed, so a refusal for load
 * reserves and consumes nothing. A watchdog thread checks every running export once a second,
 * independent of its output: it renews the reservation every {@link #LEASE_RENEWAL} and ends the
 * export when the reservation is lost or the maximum duration has passed. When the instance begins
 * to shut down, before the web server waits for its requests, every running export ends as {@code
 * SHUTDOWN}.
 */
@Component
public class SelfDisclosureRunRegistry implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(SelfDisclosureRunRegistry.class);

  /** How often a running export renews its reservation, which lapses after 2 minutes. */
  static final Duration LEASE_RENEWAL = Duration.ofSeconds(30);

  private static final Duration TICK = Duration.ofSeconds(1);

  private final Semaphore slots;
  private final int capacity;
  private final Set<SelfDisclosureRunControl> running = ConcurrentHashMap.newKeySet();
  private final ScheduledExecutorService watchdog =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            Thread thread = new Thread(task, "self-disclosure-watchdog");
            thread.setDaemon(true);
            return thread;
          });
  private volatile boolean started;
  private volatile boolean shuttingDown;

  public SelfDisclosureRunRegistry(SelfDisclosureProperties properties) {
    this.capacity = properties.maxConcurrentExports();
    this.slots = new Semaphore(capacity);
  }

  /** Takes a slot without waiting; {@code false} when all are taken or the instance shuts down. */
  boolean tryAcquire() {
    return !shuttingDown && slots.tryAcquire();
  }

  void release() {
    slots.release();
  }

  /** Exports streaming on this instance now. */
  public int active() {
    return capacity - slots.availablePermits();
  }

  boolean shuttingDown() {
    return shuttingDown;
  }

  /**
   * Watches an export from now on: ends it after {@code deadlineNanos} ({@link System#nanoTime()})
   * and when {@code renewLease} reports the reservation lost. Pair with {@link #unwatch}.
   */
  SelfDisclosureRunControl watch(long deadlineNanos, BooleanSupplier renewLease) {
    SelfDisclosureRunControl control = new SelfDisclosureRunControl();
    long[] lastRenewal = {System.nanoTime()};
    running.add(control);
    if (shuttingDown) {
      control.abort(AbortCause.SHUTDOWN, "instance shuts down");
    }
    control.watchedBy(
        watchdog.scheduleWithFixedDelay(
            () -> {
              try {
                long now = System.nanoTime();
                if (now - deadlineNanos > 0) {
                  control.abort(AbortCause.TIMEOUT, "max-duration exceeded");
                } else if (now - lastRenewal[0] >= LEASE_RENEWAL.toNanos()) {
                  if (renewLease.getAsBoolean()) {
                    lastRenewal[0] = now;
                  } else {
                    control.abort(AbortCause.ERROR, "reservation lost");
                  }
                }
              } catch (RuntimeException e) {
                log.warn("Could not renew the reservation of a self-disclosure export", e);
              }
            },
            TICK.toMillis(),
            TICK.toMillis(),
            TimeUnit.MILLISECONDS));
    return control;
  }

  void unwatch(SelfDisclosureRunControl control) {
    control.stopWatching();
    running.remove(control);
  }

  @Override
  public void start() {
    shuttingDown = false;
    started = true;
  }

  @Override
  public void stop() {
    shuttingDown = true;
    started = false;
    running.forEach(control -> control.abort(AbortCause.SHUTDOWN, "instance shuts down"));
  }

  @Override
  public boolean isRunning() {
    return started;
  }

  /** Stops first, before the graceful shutdown of the web server begins to wait. */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

  @PreDestroy
  void shutdownWatchdog() {
    watchdog.shutdownNow();
  }
}
