package io.opaa.instance;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ExecutorConfigurationSupport;

/**
 * This process in the instance register (ADR-0053, Entscheidung 3): it registers before the web
 * server, the schedulers and the task executors start, writes its heartbeat on a thread of its own,
 * and signs off after all of them have stopped. A refused registration fails the start.
 *
 * <p>Every registration gets a new identity: a stopped instance has signed off and stays dead, so a
 * context started again (a paused test context, for one) joins as a new instance.
 *
 * <p>The heartbeat thread is per instance by design (ADR-0053): every instance reports itself. It
 * is not the shared scheduler thread, so a long {@code @Scheduled} tick cannot make a live instance
 * look dead.
 */
public class CurrentInstance implements SmartLifecycle {

  /** Before every executor and the web server on start, after all of them on stop. */
  static final int PHASE = ExecutorConfigurationSupport.DEFAULT_PHASE - 1024;

  /**
   * The lifecycle phase right after this one: a component started in it finds this instance
   * registered, and so admitted next to the live ones, and still runs before the executors and the
   * web server.
   */
  public static final int REGISTERED_PHASE = PHASE + 1;

  private static final Logger log = LoggerFactory.getLogger(CurrentInstance.class);

  private final InstanceRegistry registry;
  private final ConfigurationFingerprintReader reader;
  private final InstanceVersion version;
  private final String hostName;
  private final Duration heartbeatInterval;
  private final Duration deadAfter;
  private final AtomicBoolean lost = new AtomicBoolean();

  private volatile UUID id;
  private volatile ScheduledExecutorService heartbeats;
  private volatile long lastHeartbeatNanos;

  CurrentInstance(
      InstanceRegistry registry,
      ConfigurationFingerprintReader reader,
      InstanceVersion version,
      String hostName,
      InstanceProperties properties) {
    this.registry = registry;
    this.reader = reader;
    this.version = version;
    this.hostName = Objects.requireNonNull(hostName, "hostName");
    this.heartbeatInterval = properties.heartbeatInterval();
    this.deadAfter = properties.deadAfter();
  }

  /**
   * The identity of this instance in the register.
   *
   * @throws IllegalStateException before the instance has registered
   */
  public UUID id() {
    UUID current = id;
    if (current == null) {
      throw new IllegalStateException("this instance has not registered yet");
    }
    return current;
  }

  /**
   * Whether this instance's last heartbeat lies within {@code opaa.instance.dead-after}. Once it
   * does not, the others may take this instance for gone and take over its runs.
   */
  public boolean heartbeatCurrent() {
    return isRunning()
        && !lost.get()
        && System.nanoTime() - lastHeartbeatNanos < deadAfter.toNanos();
  }

  /**
   * Whether no live instance runs a newer version than this one. During a rolling update only an
   * instance of the newest version changes the schema of what all of them share.
   */
  public boolean runsNewestVersion() {
    return registry.instances().stream()
        .filter(RegisteredInstance::alive)
        .noneMatch(instance -> InstanceVersion.compare(instance.version(), version.version()) > 0);
  }

  /**
   * Whether every live instance runs this instance's version, compared without build metadata, so
   * two builds of the same version count as one. A step that every instance has to understand the
   * same way, such as a change of the embedding model (ADR-0061, Entscheidung 6), waits for it.
   */
  public boolean allLiveInstancesRunThisVersion() {
    return registry.instances().stream()
        .filter(RegisteredInstance::alive)
        .allMatch(instance -> InstanceVersion.compare(instance.version(), version.version()) == 0);
  }

  /**
   * Whether an instance of another version than this one, compared as in {@link
   * #allLiveInstancesRunThisVersion()}, has sent a heartbeat after {@code since}.
   */
  public boolean anotherVersionRanSince(Instant since) {
    return registry.instances().stream()
        .filter(instance -> instance.heartbeatAt() != null && instance.heartbeatAt().isAfter(since))
        .anyMatch(instance -> InstanceVersion.compare(instance.version(), version.version()) != 0);
  }

  @Override
  public void start() {
    UUID registered = UUID.randomUUID();
    registry.register(registered, hostName, version, reader.read());
    id = registered;
    lost.set(false);
    lastHeartbeatNanos = System.nanoTime();
    ScheduledExecutorService executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "instance-heartbeat");
              thread.setDaemon(true);
              return thread;
            });
    long interval = heartbeatInterval.toMillis();
    executor.scheduleWithFixedDelay(this::beat, interval, interval, TimeUnit.MILLISECONDS);
    heartbeats = executor;
    List<RegisteredInstance> live =
        registry.instances().stream().filter(RegisteredInstance::alive).toList();
    log.info(
        "Registered instance {} on {} (version {}); live instances: {}",
        registered,
        hostName,
        version.version(),
        live.stream()
            .map(
                instance -> instance.id() + " on " + instance.hostName() + " " + instance.version())
            .toList());
  }

  @Override
  public void stop() {
    ScheduledExecutorService executor = heartbeats;
    heartbeats = null;
    if (executor == null) {
      return;
    }
    executor.shutdownNow();
    try {
      executor.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    try {
      registry.deregister(id);
      log.info("Signed instance {} off the register", id);
    } catch (RuntimeException e) {
      log.warn(
          "Instance {} could not sign off; it counts as dead once its heartbeat expires: {}",
          id,
          e.getMessage());
    }
  }

  @Override
  public boolean isRunning() {
    return heartbeats != null;
  }

  @Override
  public int getPhase() {
    return PHASE;
  }

  void beat() {
    try {
      if (!registry.heartbeat(id)) {
        markLost("its heartbeat expired or it was signed off");
        return;
      }
      lastHeartbeatNanos = System.nanoTime();
      registry.sweepDeadRows();
    } catch (RuntimeException e) {
      log.warn("Instance {}: heartbeat failed: {}", id, e.getMessage());
      if (System.nanoTime() - lastHeartbeatNanos >= deadAfter.toNanos()) {
        markLost("no heartbeat could be written for " + deadAfter);
      }
    }
  }

  private void markLost(String reason) {
    if (lost.compareAndSet(false, true)) {
      log.error(
          "Instance {} counts as dead in the register: {}. Other instances may take over its runs;"
              + " restart it to rejoin.",
          id,
          reason);
      ScheduledExecutorService executor = heartbeats;
      if (executor != null) {
        executor.shutdown();
      }
    }
  }
}
