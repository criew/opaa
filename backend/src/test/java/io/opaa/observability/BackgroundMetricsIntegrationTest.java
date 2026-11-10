package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The background and platform metrics of the running application (ADR-0071): the polled gauges read
 * real values from the schema, every measured cache reports hits and misses under its fixed name
 * and none whose figures would trace persons is measured, the invalidation bus and its dispatch
 * thread are measured, and every health group has a status.
 */
@OpaaIntegrationTest
class BackgroundMetricsIntegrationTest {

  /** Every measured cache; a new one joins {@link MeteredCaches} and this list. */
  private static final Set<String> CACHES =
      Set.of(
          "chat_memory",
          "external_access_quota",
          "rerank_endpoint",
          "transcription_endpoint",
          "text_recognition_endpoint",
          "metadata_filter_options",
          "asset_grants");

  /** Caches whose hits, misses or size would trace persons; none of them is measured. */
  private static final Set<String> UNMEASURED_CACHES =
      Set.of("revoked_tokens", "personal_space_provisioned", "group_memberships");

  @Autowired private MeterRegistry meterRegistry;
  @Autowired private List<PolledGauges> polledGauges;

  @Test
  void thePolledGaugesReadRealValues() {
    polledGauges.forEach(PolledGauges::refresh);

    assertThat(gauge("opaa.instances.active")).isGreaterThanOrEqualTo(1.0);
    assertThat(gauge("opaa.instances.versions")).isGreaterThanOrEqualTo(1.0);
    assertThat(gauge("opaa.instance.heartbeat.age")).isBetween(0.0, 600.0);
    assertThat(gauge("opaa.scheduled.locks.held")).isGreaterThanOrEqualTo(0.0);
    assertThat(gauge("opaa.scheduled.locks.oldest.age")).isGreaterThanOrEqualTo(0.0);
    for (String queue : List.of("mail_outbox", "source_events", "indexing")) {
      assertThat(meterRegistry.get("opaa.queue.depth").tag("queue", queue).gauge().value())
          .isGreaterThanOrEqualTo(0.0);
      assertThat(meterRegistry.get("opaa.queue.oldest.age").tag("queue", queue).gauge().value())
          .isGreaterThanOrEqualTo(0.0);
    }
    for (String meter :
        List.of(
            "opaa.inventory.libraries",
            "opaa.inventory.documents",
            "opaa.inventory.chunks",
            "opaa.inventory.storage.used")) {
      assertThat(meterRegistry.get(meter).tag("kind", "shared").gauge().value())
          .isGreaterThanOrEqualTo(0.0);
      assertThat(meterRegistry.get(meter).tag("kind", "private").gauge()).isNotNull();
    }
    assertThat(gauge("opaa.inventory.spaces")).isGreaterThanOrEqualTo(0.0);
    assertThat(
            meterRegistry.get("opaa.inventory.storage.limit").tag("kind", "shared").gauge().value())
        .isGreaterThanOrEqualTo(0.0);
  }

  @Test
  void everyCacheReportsItsHitsAndMisses() {
    Set<String> measured =
        meterRegistry.find("cache.gets").meters().stream()
            .map(Meter::getId)
            .filter(id -> "hit".equals(id.getTag("result")))
            .map(id -> id.getTag("cache"))
            .collect(Collectors.toSet());

    assertThat(measured).containsAll(CACHES).doesNotContainAnyElementsOf(UNMEASURED_CACHES);
    assertThat(meterRegistry.find("cache.size").tag("cache", "chat_memory").meter()).isNull();
    assertThat(meterRegistry.find("cache.evictions").tag("cache", "chat_memory").meter()).isNull();
    assertThat(meterRegistry.find("opaa.conversations.active").meter()).isNull();
  }

  @Test
  void theBusAndEveryHealthGroupAreMeasured() {
    polledGauges.forEach(PolledGauges::refresh);

    assertThat(gauge("opaa.invalidation_bus.connected")).isEqualTo(1.0);
    for (String executor : List.of("cache-invalidation", "embedding-reindex")) {
      assertThat(meterRegistry.find("executor.completed").tag("name", executor).meter())
          .as(executor)
          .isNotNull();
    }
    Set<String> groups =
        meterRegistry.find(HealthGroupMetricsConfiguration.STATUS).meters().stream()
            .map(meter -> meter.getId().getTag("health_group"))
            .collect(Collectors.toSet());
    assertThat(groups).contains("liveness", "readiness");
    assertThat(
            meterRegistry
                .get(HealthGroupMetricsConfiguration.STATUS)
                .tag("health_group", "liveness")
                .gauge()
                .value())
        .isEqualTo(1.0);
  }

  private double gauge(String name) {
    return meterRegistry.get(name).gauge().value();
  }
}
