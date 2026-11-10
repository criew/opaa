package io.opaa.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.util.TreeSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The status of every health group as {@code opaa.health.group.status} with {@code health_group}
 * (ADR-0071): {@code 1} up, {@code 0} down or out of service, {@code -1} unknown (not set up). An
 * alert can watch a group without a probe from outside. Read every {@link #INTERVAL} rather than
 * per scrape: the groups {@code embedding-model} and {@code vector-store} call the model, held for
 * a short while by {@code CachedProbe}. The tag is not {@code group}, which names a group of
 * persons everywhere else ({@code ForbiddenMetricTags}).
 */
@Configuration(proxyBeanMethods = false)
public class HealthGroupMetricsConfiguration {

  static final String STATUS = "opaa.health.group.status";

  static final Duration INTERVAL = Duration.ofMinutes(5);

  @Bean
  PolledGauges healthGroupGauges(
      MeterRegistry registry,
      ObjectProvider<HealthEndpoint> endpoint,
      ObjectProvider<HealthEndpointGroups> groups) {
    PolledGauges gauges = new PolledGauges(registry, INTERVAL);
    HealthEndpointGroups named = groups.getIfAvailable();
    if (named == null) {
      return gauges;
    }
    for (String group : new TreeSet<>(named.getNames())) {
      gauges.gauge(
          STATUS,
          Tags.of("health_group", group),
          null,
          "Status of the health group: 1 up, 0 down or out of service, -1 unknown",
          () -> value(endpoint.getObject().healthForPath(group)));
    }
    return gauges;
  }

  static double value(HealthDescriptor health) {
    if (health == null) {
      return Double.NaN;
    }
    Status status = health.getStatus();
    if (Status.UP.equals(status)) {
      return 1;
    }
    if (Status.DOWN.equals(status) || Status.OUT_OF_SERVICE.equals(status)) {
      return 0;
    }
    return -1;
  }
}
