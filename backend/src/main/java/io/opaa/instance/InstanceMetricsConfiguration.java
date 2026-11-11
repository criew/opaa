package io.opaa.instance;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.opaa.observability.PolledGauges;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The instances, the invalidation bus and the run locks as metrics (ADR-0071, ADR-0053). The
 * register and lock gauges are installation-wide and every instance reports them, read from the
 * database by its clock; a dashboard takes {@code max} over the pods. The bus and the heartbeat age
 * are this instance's own.
 */
@Configuration(proxyBeanMethods = false)
public class InstanceMetricsConfiguration {

  /** How often the register and the lock table are read. */
  static final Duration INTERVAL = Duration.ofSeconds(30);

  static final String ALIVE =
      "stopped_at IS NULL AND heartbeat_at >= clock_timestamp() - make_interval(secs => ?)";

  static final String ACTIVE_SQL = "SELECT count(*) FROM backend_instances WHERE " + ALIVE;

  static final String VERSIONS_SQL =
      "SELECT count(DISTINCT version) FROM backend_instances WHERE " + ALIVE;

  static final String HEARTBEAT_AGE_SQL =
      "SELECT extract(epoch FROM clock_timestamp() - heartbeat_at) FROM backend_instances"
          + " WHERE id = ?";

  /** ShedLock writes its times in UTC by the database clock ({@code usingDbTime}). */
  private static final String HELD = " FROM shedlock WHERE lock_until > timezone('utc', now())";

  static final String LOCKS_HELD_SQL = "SELECT count(*)" + HELD;

  static final String OLDEST_LOCK_AGE_SQL =
      "SELECT coalesce(extract(epoch FROM timezone('utc', now()) - min(locked_at)), 0)" + HELD;

  @Bean
  PolledGauges instanceGauges(
      DataSource dataSource,
      MeterRegistry registry,
      InstanceProperties properties,
      CurrentInstance currentInstance) {
    JdbcTemplate jdbc = PolledGauges.reads(dataSource);
    double deadAfter = properties.deadAfter().toMillis() / 1000.0;
    return new PolledGauges(registry, INTERVAL)
        .gauge(
            "opaa.instances.active",
            Tags.empty(),
            null,
            "Instances alive in the register by the database clock",
            PolledGauges.sql(jdbc, ACTIVE_SQL, deadAfter))
        .gauge(
            "opaa.instances.versions",
            Tags.empty(),
            null,
            "Different versions among the instances alive; above 1 during a rolling update",
            PolledGauges.sql(jdbc, VERSIONS_SQL, deadAfter))
        .gauge(
            "opaa.instance.heartbeat.age",
            Tags.empty(),
            "seconds",
            "Time since this instance last wrote its heartbeat, by the database clock",
            () -> PolledGauges.sql(jdbc, HEARTBEAT_AGE_SQL, currentInstance.id()).getAsDouble())
        .gauge(
            "opaa.scheduled.locks.held",
            Tags.empty(),
            null,
            "Run locks held, a run's minimum lock period after it ended included",
            PolledGauges.sql(jdbc, LOCKS_HELD_SQL))
        .gauge(
            "opaa.scheduled.locks.oldest.age",
            Tags.empty(),
            "seconds",
            "Time since the oldest held run lock was taken, 0 without one",
            PolledGauges.sql(jdbc, OLDEST_LOCK_AGE_SQL));
  }

  /** Whether this instance hears the bus; at 0 its caches read past themselves. */
  @Bean
  InvalidationBusMetrics invalidationBusMetrics(
      MeterRegistry registry, ObjectProvider<PostgresInvalidationBus> bus) {
    return new InvalidationBusMetrics(registry, bus);
  }

  /** The bus gauge and its dispatch thread, bound once the bus has started. */
  static final class InvalidationBusMetrics {

    private final MeterRegistry registry;
    private final ObjectProvider<PostgresInvalidationBus> bus;

    InvalidationBusMetrics(MeterRegistry registry, ObjectProvider<PostgresInvalidationBus> bus) {
      this.registry = registry;
      this.bus = bus;
    }

    @EventListener(ApplicationStartedEvent.class)
    public void bind() {
      PostgresInvalidationBus listening = bus.getIfAvailable();
      if (listening == null) {
        return;
      }
      Gauge.builder("opaa.invalidation_bus.connected", listening, b -> b.isListening() ? 1 : 0)
          .description("1 while this instance listens on the bus, 0 while its caches read past")
          .strongReference(true)
          .register(registry);
      ExecutorService dispatcher = listening.dispatcher();
      if (dispatcher != null) {
        new ExecutorServiceMetrics(dispatcher, "cache-invalidation", Tags.empty()).bindTo(registry);
      }
    }
  }
}
