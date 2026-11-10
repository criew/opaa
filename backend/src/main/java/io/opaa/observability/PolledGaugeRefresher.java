package io.opaa.observability;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Reads every {@link PolledGauges} bean when it is due, on a scheduler thread of its own: a read
 * may wait for the database or a health probe, and must not hold back the shared scheduler thread.
 * Runs on every instance without a lock (ADR-0053, je Instanz): it only reads, and each instance
 * reports its own gauges.
 */
@Configuration(proxyBeanMethods = false)
public class PolledGaugeRefresher {

  /** The scheduler of the reads. */
  public static final String SCHEDULER = "polledGaugeScheduler";

  /** How often the refresher looks for due gauges; no {@link PolledGauges} is read more often. */
  static final long TICK_SECONDS = 15;

  private final ObjectProvider<PolledGauges> gauges;
  private final Clock clock;

  PolledGaugeRefresher(ObjectProvider<PolledGauges> gauges, Clock clock) {
    this.gauges = gauges;
    this.clock = clock;
  }

  @Bean(name = SCHEDULER, destroyMethod = "shutdown")
  static TaskScheduler polledGaugeScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("polled-gauges-");
    scheduler.setDaemon(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    scheduler.initialize();
    return scheduler;
  }

  /** Reads every set of gauges whose interval has passed. */
  @Scheduled(
      initialDelay = 0,
      fixedDelay = TICK_SECONDS,
      timeUnit = java.util.concurrent.TimeUnit.SECONDS,
      scheduler = SCHEDULER)
  public void refresh() {
    gauges.orderedStream().forEach(polled -> polled.refreshIfDue(clock.instant()));
  }
}
