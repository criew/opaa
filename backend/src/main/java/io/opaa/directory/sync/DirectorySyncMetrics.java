package io.opaa.directory.sync;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.opaa.api.types.DirectorySyncOutcome;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Directory synchronisation as metrics, by {@code provider}: the type of the directory access in
 * lower case ({@code keycloak}), {@value #NO_PROVIDER} for a provider without one; never the
 * provider row itself (ADR-0071, Entscheidung 2). Runs count as they end; the last applied run and
 * the age of the oldest plan waiting for a decision are read from the database by {@link #refresh}
 * on every instance (ADR-0053), so each pod reports the same value.
 */
@Component
public class DirectorySyncMetrics {

  private static final Logger log = LoggerFactory.getLogger(DirectorySyncMetrics.class);

  /** The {@code provider} of a run whose provider has no directory access stored. */
  static final String NO_PROVIDER = "none";

  /** Bounds a read blocked by a lock, so it never holds the shared scheduler thread for long. */
  static final int QUERY_TIMEOUT_SECONDS = 10;

  static final String PROVIDER_SQL =
      "SELECT connector_type FROM directory_connectors WHERE provider_id = ?";

  /** Only {@code APPLIED} advances {@code last_applied_at}: a waiting plan leaves it standing. */
  static final String LAST_APPLIED_SQL =
      "SELECT c.connector_type, extract(epoch FROM max(s.last_applied_at))"
          + " FROM directory_sync_status s"
          + " LEFT JOIN directory_connectors c ON c.provider_id = s.provider_id"
          + " WHERE s.last_applied_at IS NOT NULL GROUP BY c.connector_type";

  /** Every provider with an access or a plan: the age of its oldest waiting plan, else 0. */
  static final String PENDING_AGE_SQL =
      "SELECT coalesce(c.connector_type, ''),"
          + " coalesce(extract(epoch FROM clock_timestamp() - min(p.created_at)), 0)"
          + " FROM directory_connectors c"
          + " FULL JOIN directory_sync_pending_plans p ON p.provider_id = c.provider_id"
          + " GROUP BY coalesce(c.connector_type, '')";

  private final MeterRegistry meterRegistry;
  private final JdbcTemplate jdbc;
  private final MultiGauge lastSuccess;
  private final MultiGauge pendingAge;
  private final AtomicBoolean failing = new AtomicBoolean();
  private volatile Set<String> lastSuccessProviders = Set.of();
  private volatile Set<String> pendingAgeProviders = Set.of();

  @Autowired
  public DirectorySyncMetrics(DataSource dataSource, MeterRegistry meterRegistry) {
    this(reads(dataSource), meterRegistry);
  }

  DirectorySyncMetrics(JdbcTemplate jdbc, MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    this.jdbc = jdbc;
    this.lastSuccess =
        MultiGauge.builder("opaa.directory_sync.last_success")
            .baseUnit("seconds")
            .description("Unix time of the latest applied directory synchronisation run")
            .register(meterRegistry);
    this.pendingAge =
        MultiGauge.builder("opaa.directory_sync.plan.pending.age")
            .baseUnit("seconds")
            .description("Age of the oldest synchronisation plan waiting for a decision, else 0")
            .register(meterRegistry);
  }

  /** One ended run of {@code providerId}'s directory with its {@code outcome}. */
  public void recordRun(UUID providerId, DirectorySyncOutcome outcome, Duration duration) {
    String provider = providerOf(providerId);
    String result = outcome.name().toLowerCase(Locale.ROOT);
    Counter.builder("opaa.directory_sync.runs")
        .tag("provider", provider)
        .tag("result", result)
        .description("Ended directory synchronisation runs")
        .register(meterRegistry)
        .increment();
    Timer.builder("opaa.directory_sync.duration")
        .tag("provider", provider)
        .tag("result", result)
        .description("Duration of a directory synchronisation run")
        .register(meterRegistry)
        .record(duration);
  }

  /** The {@code provider} tag of {@code providerId}; {@value #NO_PROVIDER} when unreadable. */
  private String providerOf(UUID providerId) {
    try {
      List<String> types = jdbc.queryForList(PROVIDER_SQL, String.class, providerId);
      return types.isEmpty() ? NO_PROVIDER : tagOf(types.getFirst());
    } catch (RuntimeException e) {
      log.debug("Directory sync metrics could not read the access type of a provider", e);
      return NO_PROVIDER;
    }
  }

  private static String tagOf(String connectorType) {
    return connectorType == null || connectorType.isEmpty()
        ? NO_PROVIDER
        : connectorType.toLowerCase(Locale.ROOT);
  }

  /**
   * Reads both gauges anew. Runs on every instance without a lock: it only reads, and each instance
   * reports its own gauges (ScheduledRunLockStructureTest#RUNS_ON_EVERY_INSTANCE). A failed read
   * reports NaN under the providers of the last one and warns once until a read succeeds again.
   */
  @Scheduled(initialDelay = 0, fixedDelayString = "PT1M")
  public void refresh() {
    try {
      Map<String, Double> applied = read(LAST_APPLIED_SQL);
      Map<String, Double> pending = read(PENDING_AGE_SQL);
      lastSuccess.register(rows(applied), true);
      pendingAge.register(rows(pending), true);
      lastSuccessProviders = Set.copyOf(applied.keySet());
      pendingAgeProviders = Set.copyOf(pending.keySet());
      if (failing.compareAndSet(true, false)) {
        log.info("Directory sync metrics are read again");
      }
    } catch (RuntimeException e) {
      lastSuccess.register(rows(unknown(lastSuccessProviders)), true);
      pendingAge.register(rows(unknown(pendingAgeProviders)), true);
      if (failing.compareAndSet(false, true)) {
        log.warn("Directory sync metrics could not be read, reporting NaN until they are", e);
      }
    }
  }

  /** {@code sql}'s pairs by provider type; the largest value wins within a type. */
  private Map<String, Double> read(String sql) {
    Map<String, Double> byProvider = new TreeMap<>();
    RowCallbackHandler collect =
        row -> {
          byProvider.merge(tagOf(row.getString(1)), row.getDouble(2), Math::max);
        };
    jdbc.query(sql, collect);
    return byProvider;
  }

  /** NaN under each of {@code providers}: "unknown", not a stale or zero value. */
  private static Map<String, Double> unknown(Set<String> providers) {
    Map<String, Double> byProvider = new TreeMap<>();
    providers.forEach(provider -> byProvider.put(provider, Double.NaN));
    return byProvider;
  }

  private static List<MultiGauge.Row<?>> rows(Map<String, Double> byProvider) {
    return byProvider.entrySet().stream()
        .<MultiGauge.Row<?>>map(
            entry -> MultiGauge.Row.of(Tags.of("provider", entry.getKey()), entry.getValue()))
        .toList();
  }

  /** A template of its own: the query timeout would otherwise bound every other reader as well. */
  static JdbcTemplate reads(DataSource dataSource) {
    JdbcTemplate reads = new JdbcTemplate(dataSource);
    reads.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    return reads;
  }
}
