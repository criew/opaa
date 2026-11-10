package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
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
 * How fresh {@code opaa.health.group.status} is: a probe group read while the instance still starts
 * shows its later state within a minute of the refresher's ticks; only the probe groups, a {@link
 * LocalHealthGroup} and a {@link PassiveHealthGroup} are read that often, every other group every
 * five minutes; a passive group is never probed and accepts a last result of up to ten minutes.
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
    when(groups.getNames())
        .thenReturn(
            Set.of("readiness", "liveness", "chat-model", "upload-store", "embedding-model"));
    when(endpoint.healthForPath("readiness")).thenAnswer(call -> descriptor(readiness.get()));
    when(endpoint.healthForPath("liveness")).thenAnswer(call -> descriptor(Status.UP));
    when(endpoint.healthForPath("chat-model")).thenAnswer(call -> descriptor(Status.UP));
    when(endpoint.healthForPath("upload-store")).thenAnswer(call -> descriptor(Status.UP));
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
    beans.addBean("chat", (LocalHealthGroup) () -> "chat-model");
  }

  // regression guard for #3710: the read during startup must not stand for minutes once ready
  @Test
  void aReadinessReadDuringStartupIsReplacedWithinAMinuteOfTicks() {
    List<PolledGauges> gauges = gauges();
    readiness.set(Status.OUT_OF_SERVICE);
    tick(gauges, START);
    assertThat(status("readiness")).isZero();

    readiness.set(Status.UP);
    ticksForOneMinute(gauges);

    assertThat(status("readiness")).isEqualTo(1.0);
  }

  @Test
  void probeLocalAndPassiveGroupsAreReadOftenAndAGroupWithoutMarkStaysSlow() {
    List<PolledGauges> gauges = gauges();
    readiness.set(Status.UP);
    tick(gauges, START);
    ticksForOneMinute(gauges);

    verify(endpoint, times(3)).healthForPath("readiness");
    verify(endpoint, times(3)).healthForPath("liveness");
    verify(endpoint, times(3)).healthForPath("chat-model");
    verify(endpoint, times(1)).healthForPath("upload-store");
    assertThat(passiveMaxAges).hasSize(3);
    assertThat(registry.find(HealthGroupMetricsConfiguration.STATUS).gauges()).hasSize(5);
  }

  @Test
  void aPassiveGroupIsNeverProbedAndAcceptsAResultOfTenMinutes() {
    List<PolledGauges> gauges = gauges();
    readiness.set(Status.UP);
    tick(gauges, START);

    assertThat(status("embedding-model")).isEqualTo(1.0);
    assertThat(passiveMaxAges).containsOnly(Duration.ofMinutes(10));
    verify(endpoint, never()).healthForPath("embedding-model");
    verify(endpoint, never()).health();
  }

  @Test
  void readsOneRightAfterTheOtherAreNeverRefused() {
    ExecutorService thread = HealthGroupMetricsConfiguration.readThread();
    try {
      for (int i = 0; i < 20_000; i++) {
        assertThat(
                HealthGroupMetricsConfiguration.withinLimit(
                    thread, HealthGroupMetricsConfiguration.TIME_LIMIT, () -> 1.0))
            .isEqualTo(1.0);
      }
    } finally {
      thread.shutdownNow();
    }
  }

  @Test
  void aHangingReadHoldsAtMostOneFurtherReadAndRefusesTheRestAtOnce() throws Exception {
    ExecutorService thread = HealthGroupMetricsConfiguration.readThread();
    CountDownLatch release = new CountDownLatch(1);
    try {
      thread.submit(
          () -> {
            release.await();
            return null;
          });
      Duration shortLimit = Duration.ofMillis(200);
      assertThatThrownBy(
              () -> HealthGroupMetricsConfiguration.withinLimit(thread, shortLimit, () -> 1.0))
          .hasMessageContaining("not read within");
      long before = System.nanoTime();
      assertThatThrownBy(
              () -> HealthGroupMetricsConfiguration.withinLimit(thread, shortLimit, () -> 1.0))
          .hasMessageContaining("still running");
      assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(shortLimit);
    } finally {
      release.countDown();
      thread.shutdownNow();
    }
  }

  private List<PolledGauges> gauges() {
    HealthGroupMetricsConfiguration configuration = new HealthGroupMetricsConfiguration();
    return List.of(
        configuration.localHealthGroupGauges(
            registry,
            beans.getBeanProvider(HealthEndpoint.class),
            beans.getBeanProvider(HealthEndpointGroups.class),
            beans.getBeanProvider(PassiveHealthGroup.class),
            beans.getBeanProvider(LocalHealthGroup.class)),
        configuration.healthGroupGauges(
            registry,
            beans.getBeanProvider(HealthEndpoint.class),
            beans.getBeanProvider(HealthEndpointGroups.class),
            beans.getBeanProvider(PassiveHealthGroup.class),
            beans.getBeanProvider(LocalHealthGroup.class)));
  }

  private static void tick(List<PolledGauges> gauges, Instant now) {
    gauges.forEach(polled -> polled.refreshIfDue(now));
  }

  /** The refresher's ticks after {@link #START} up to one minute later. */
  private static void ticksForOneMinute(List<PolledGauges> gauges) {
    for (Instant tick = START.plusSeconds(PolledGaugeRefresher.TICK_SECONDS);
        !tick.isAfter(START.plus(Duration.ofMinutes(1)));
        tick = tick.plusSeconds(PolledGaugeRefresher.TICK_SECONDS)) {
      tick(gauges, tick);
    }
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
