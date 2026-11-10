package io.opaa.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The status of every health group as {@code opaa.health.group.status} with {@code health_group}
 * (ADR-0071): {@code 1} up, {@code 0} down or out of service, {@code -1} unknown. An alert can
 * watch a group without a probe from outside. Read every {@link #INTERVAL} on a thread of its own,
 * each group within {@link #TIME_LIMIT}, else {@code NaN}. A {@link PassiveHealthGroup}, whose
 * probe calls a model, is not probed: it shows the last result of at most {@link #PASSIVE_MAX_AGE},
 * else {@code -1}. The tag is not {@code group}, which names a group of persons ({@code
 * ForbiddenMetricTags}).
 */
@Configuration(proxyBeanMethods = false)
public class HealthGroupMetricsConfiguration {

  static final String STATUS = "opaa.health.group.status";

  static final Duration INTERVAL = Duration.ofMinutes(5);

  static final Duration TIME_LIMIT = Duration.ofSeconds(10);

  static final Duration PASSIVE_MAX_AGE = INTERVAL.multipliedBy(2);

  /**
   * The thread of the reads, without a queue: a read arriving while one still runs is refused, so a
   * hanging group never piles up reads behind it. A daemon and no bean, so it neither holds back
   * the shutdown nor displaces the application's task executor.
   */
  private static final ExecutorService READS =
      new ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new SynchronousQueue<>(),
          runnable -> {
            Thread thread = new Thread(runnable, "health-group-metrics");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());

  @Bean
  PolledGauges healthGroupGauges(
      MeterRegistry registry,
      ObjectProvider<HealthEndpoint> endpoint,
      ObjectProvider<HealthEndpointGroups> groups,
      ObjectProvider<PassiveHealthGroup> passive) {
    PolledGauges gauges = new PolledGauges(registry, INTERVAL);
    HealthEndpointGroups named = groups.getIfAvailable();
    if (named == null) {
      return gauges;
    }
    Map<String, PassiveHealthGroup> passiveByGroup =
        passive
            .orderedStream()
            .collect(
                Collectors.toMap(
                    PassiveHealthGroup::healthGroup, Function.identity(), (a, b) -> a));
    for (String group : new TreeSet<>(named.getNames())) {
      PassiveHealthGroup cached = passiveByGroup.get(group);
      gauges.gauge(
          STATUS,
          Tags.of("health_group", group),
          null,
          "Status of the health group: 1 up, 0 down or out of service, -1 unknown",
          cached != null
              ? () ->
                  cached
                      .lastStatus(PASSIVE_MAX_AGE)
                      .map(HealthGroupMetricsConfiguration::value)
                      .orElse(-1.0)
              : () ->
                  withinLimit(
                      READS, TIME_LIMIT, () -> value(endpoint.getObject().healthForPath(group))));
    }
    return gauges;
  }

  /**
   * {@code read} on the own thread; a read beyond {@code limit} is interrupted and fails, one
   * refused because the thread is still busy fails at once.
   */
  static double withinLimit(ExecutorService executor, Duration limit, DoubleSupplier read) {
    Future<Double> future;
    try {
      future = executor.submit(read::getAsDouble);
    } catch (RejectedExecutionException e) {
      throw new IllegalStateException("an earlier health group read is still running", e);
    }
    try {
      return future.get(limit.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new IllegalStateException("health group not read within " + limit, e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("health group could not be read", e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while reading a health group", e);
    }
  }

  static double value(HealthDescriptor health) {
    return health == null ? Double.NaN : value(health.getStatus());
  }

  static double value(Status status) {
    if (Status.UP.equals(status)) {
      return 1;
    }
    if (Status.DOWN.equals(status) || Status.OUT_OF_SERVICE.equals(status)) {
      return 0;
    }
    return -1;
  }
}
