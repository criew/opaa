package io.opaa.instance;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * Last success, failures and expected interval of every {@code @Scheduled} run as {@code
 * opaa.scheduled.*} with the tag {@code job} (ADR-0071): the name of its run lock, or for a run
 * without one {@code <class>.<method>} in kebab case. Hooks into the observation Spring opens
 * around every run, so no run needs code of its own. A run fails when it throws, or when it catches
 * a failure itself and says so through {@link ScheduledRunFailures#report()}.
 *
 * <p>Only a scheduled run has series: a job whose bean a property or feature flag leaves out, or
 * whose cron is {@code "-"}, has none. A locked run whose lock another instance holds neither
 * succeeds nor fails here. Per instance (ADR-0053): under the lock one instance runs each due time,
 * so a dashboard takes {@code max by (job)} of the last success over the pods. Until a run succeeds
 * here its last success is the start of this instance, so a run that never succeeds since the start
 * becomes overdue like one that stopped succeeding.
 */
@Component
public class ScheduledJobMetrics implements ObservationHandler<ScheduledTaskObservationContext> {

  public static final String LAST_SUCCESS = "opaa.scheduled.last_success";
  public static final String FAILURES = "opaa.scheduled.failures";
  public static final String EXPECTED_INTERVAL = "opaa.scheduled.expected_interval";

  private static final Logger log = LoggerFactory.getLogger(ScheduledJobMetrics.class);

  private final MeterRegistry registry;
  private final Clock clock;
  private final ObjectProvider<ScheduledTaskHolder> holders;
  private final Map<String, Job> jobs = new ConcurrentHashMap<>();
  private final double started;

  public ScheduledJobMetrics(
      MeterRegistry registry, Clock clock, ObjectProvider<ScheduledTaskHolder> holders) {
    this.registry = registry;
    this.clock = clock;
    this.holders = holders;
    this.started = clock.millis() / 1000.0;
  }

  /**
   * Registers the series of every scheduled run with its expected interval, before its first run: a
   * monthly run shows up right after the start, its last success only once it ran here.
   */
  @EventListener(ApplicationStartedEvent.class)
  public void registerScheduledRuns() {
    holders
        .orderedStream()
        .flatMap(holder -> holder.getScheduledTasks().stream())
        .forEach(this::register);
  }

  private void register(ScheduledTask task) {
    Optional<Method> method = scheduledMethod(String.valueOf(task.getTask().getRunnable()));
    if (method.isEmpty()) {
      return;
    }
    Job job = job(method.get());
    expectedInterval(task)
        .ifPresent(
            interval ->
                Gauge.builder(EXPECTED_INTERVAL, () -> interval.toMillis() / 1000.0)
                    .tag("job", job.name)
                    .baseUnit("seconds")
                    .description("Interval between two due times of the scheduled run")
                    .register(registry));
  }

  @Override
  public boolean supportsContext(Observation.Context context) {
    return context instanceof ScheduledTaskObservationContext;
  }

  @Override
  public void onStart(ScheduledTaskObservationContext context) {
    ScheduledRunLockAcquisitions.clear();
    ScheduledRunFailures.clear();
  }

  @Override
  public void onStop(ScheduledTaskObservationContext context) {
    try {
      Job job = job(context.getMethod());
      if (context.getError() != null || ScheduledRunFailures.reported()) {
        job.failures.increment();
      } else if (job.lockName == null || ScheduledRunLockAcquisitions.acquired(job.lockName)) {
        job.lastSuccess.set(clock.millis() / 1000.0);
      }
    } finally {
      ScheduledRunLockAcquisitions.clear();
      ScheduledRunFailures.clear();
    }
  }

  private Job job(Method method) {
    return jobs.computeIfAbsent(key(method), key -> new Job(method, registry, started));
  }

  /** The key Spring's scheduled method runnable prints itself as: class name, dot, method name. */
  private static String key(Method method) {
    return method.getDeclaringClass().getName() + "." + method.getName();
  }

  /** The method behind a scheduled task, found by the description Spring gives the task. */
  private static Optional<Method> scheduledMethod(String description) {
    int dot = description.lastIndexOf('.');
    if (dot <= 0) {
      return Optional.empty();
    }
    try {
      Class<?> type =
          ClassUtils.forName(
              description.substring(0, dot), ScheduledJobMetrics.class.getClassLoader());
      return Optional.of(type.getMethod(description.substring(dot + 1)));
    } catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
      log.debug("Scheduled task {} is not a scheduled method; it has no job series", description);
      return Optional.empty();
    }
  }

  /** The time between two due times: the fixed delay or rate, or two consecutive cron times. */
  private Optional<Duration> expectedInterval(ScheduledTask task) {
    if (task.getTask() instanceof IntervalTask interval) {
      return Optional.of(interval.getIntervalDuration());
    }
    if (task.getTask() instanceof CronTask cron) {
      CronExpression expression = CronExpression.parse(cron.getExpression());
      ZonedDateTime first = expression.next(ZonedDateTime.now(clock));
      ZonedDateTime second = first == null ? null : expression.next(first);
      return second == null ? Optional.empty() : Optional.of(Duration.between(first, second));
    }
    return Optional.empty();
  }

  /** The run lock's name, or {@code <class>.<method>} in kebab case for a run without one. */
  static String jobName(Method method) {
    String lock = lockName(method);
    if (lock != null) {
      return lock;
    }
    return kebab(ClassUtils.getUserClass(method.getDeclaringClass()).getSimpleName())
        + "."
        + kebab(method.getName());
  }

  private static String lockName(Method method) {
    SchedulerLock lock = AnnotatedElementUtils.findMergedAnnotation(method, SchedulerLock.class);
    return lock == null || lock.name().isBlank() ? null : lock.name();
  }

  private static String kebab(String camel) {
    return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
  }

  /** The series of one job, registered on first use. */
  private static final class Job {
    private final String name;
    private final String lockName;
    private final AtomicReference<Double> lastSuccess;
    private final Counter failures;

    private Job(Method method, MeterRegistry registry, double started) {
      this.name = jobName(method);
      this.lastSuccess = new AtomicReference<>(started);
      this.lockName = lockName(method);
      Gauge.builder(LAST_SUCCESS, lastSuccess, AtomicReference::get)
          .tag("job", name)
          .description(
              "Unix time of the last successful run on this instance, at the earliest its start")
          .register(registry);
      this.failures =
          Counter.builder(FAILURES)
              .tag("job", name)
              .description("Runs that ended with an exception")
              .register(registry);
    }
  }
}
