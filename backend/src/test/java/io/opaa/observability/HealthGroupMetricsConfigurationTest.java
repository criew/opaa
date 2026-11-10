package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.contributor.Status;

/**
 * How fresh {@code opaa.health.group.status} is: a probed group read while the instance still
 * starts shows its later state within a minute of the refresher's ticks, and a passive group is
 * never probed and accepts a last result of up to ten minutes.
 */
class HealthGroupMetricsConfigurationTest {

  private static final Instant START = Instant.parse("2026-11-10T08:00:00Z");

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final HealthEndpoint endpoint = mock(HealthEndpoint.class);
  private final HealthEndpointGroups groups = mock(HealthEndpointGroups.class);
  private final AtomicReference<Status> readiness = new AtomicReference<>();
  private final List<Duration> passiveMaxAges = new ArrayList<>();
  private final StaticListableBeanFactory beans = new StaticListableBeanFactory();

  @BeforeEach
  void groups() {
    when(groups.getNames()).thenReturn(Set.of("readiness", "embedding-model"));
    when(endpoint.healthForPath("readiness")).thenAnswer(call -> descriptor(readiness.get()));
    beans.addBean("endpoint", endpoint);
    beans.addBean("groups", groups);
    beans.addBean(
        "embeddings",
        new PassiveHealthGroup() {
          @Override
          public String healthGroup() {
            return "embedding-model";
          }

          @Override
          public Optional<Status> lastStatus(Duration maxAge) {
            passiveMaxAges.add(maxAge);
            return Optional.of(Status.UP);
          }
        });
  }

  // regression guard for #3710: the read during startup must not stand for minutes once ready
  @Test
  void aReadinessReadDuringStartupIsReplacedWithinAMinuteOfTicks() {
    PolledGauges gauges = gauges();
    readiness.set(Status.OUT_OF_SERVICE);
    gauges.refreshIfDue(START);
    assertThat(status("readiness")).isZero();

    readiness.set(Status.UP);
    for (Instant tick = START.plusSeconds(PolledGaugeRefresher.TICK_SECONDS);
        !tick.isAfter(START.plus(Duration.ofMinutes(1)));
        tick = tick.plusSeconds(PolledGaugeRefresher.TICK_SECONDS)) {
      gauges.refreshIfDue(tick);
    }

    assertThat(status("readiness")).isEqualTo(1.0);
  }

  @Test
  void aPassiveGroupIsNeverProbedAndAcceptsAResultOfTenMinutes() {
    PolledGauges gauges = gauges();
    readiness.set(Status.UP);
    gauges.refreshIfDue(START);

    assertThat(status("embedding-model")).isEqualTo(1.0);
    assertThat(passiveMaxAges).containsOnly(Duration.ofMinutes(10));
    verify(endpoint, never()).healthForPath("embedding-model");
    verify(endpoint, never()).health();
  }

  private PolledGauges gauges() {
    return new HealthGroupMetricsConfiguration()
        .healthGroupGauges(
            registry,
            beans.getBeanProvider(HealthEndpoint.class),
            beans.getBeanProvider(HealthEndpointGroups.class),
            beans.getBeanProvider(PassiveHealthGroup.class));
  }

  private double status(String group) {
    return registry
        .get(HealthGroupMetricsConfiguration.STATUS)
        .tag("health_group", group)
        .gauge()
        .value();
  }

  private static HealthDescriptor descriptor(Status status) {
    HealthDescriptor descriptor = mock(CompositeHealthDescriptor.class);
    when(descriptor.getStatus()).thenReturn(status);
    return descriptor;
  }
}
