package io.opaa.selfdisclosure;

import java.util.concurrent.Semaphore;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The exports this instance streams right now (ADR-0053, Entscheidung 12: a bound per instance,
 * kept in memory on purpose). A slot is taken before the ticket is redeemed, so a refusal for load
 * reserves and consumes nothing. When the instance begins to shut down, before the web server waits
 * for its requests, every running export is told to stop and ends as {@code SHUTDOWN}.
 */
@Component
public class SelfDisclosureRunRegistry implements SmartLifecycle {

  private final Semaphore slots;
  private final int capacity;
  private volatile boolean running;
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

  @Override
  public void start() {
    running = true;
  }

  @Override
  public void stop() {
    shuttingDown = true;
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** Stops first, before the graceful shutdown of the web server begins to wait. */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }
}
