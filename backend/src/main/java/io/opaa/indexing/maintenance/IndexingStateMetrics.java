package io.opaa.indexing.maintenance;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.opaa.api.types.ScheduleFrequency;
import io.opaa.indexing.job.LibraryScheduleCodec;
import io.opaa.observability.IndexingMetrics;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * The state of indexing as gauges: last successful run and overdue scheduled libraries per
 * connector type and the chunks a model change still has to embed, read from the database by {@link
 * #refresh} on every instance (ADR-0053), so each pod reports the same value and a scrape never
 * waits for the database; plus the runs this instance's pool executes and holds queued.
 */
@Component
public class IndexingStateMetrics {

  private static final Logger log = LoggerFactory.getLogger(IndexingStateMetrics.class);

  /** Bounds a read blocked by a lock, so it never holds the shared scheduler thread for long. */
  static final int QUERY_TIMEOUT_SECONDS = 10;

  /**
   * A scheduled library is overdue once its last successful run is older than this many of its
   * intervals; one missed due time alone is not yet overdue.
   */
  static final int OVERDUE_FACTOR = 2;

  /**
   * Every library with its last successful run, its schedule ({@code null} for none or one about to
   * be erased) and its creation; the partial index on {@code (library_id, completed_at)} serves the
   * maximum.
   */
  static final String LIBRARIES_SQL =
      "SELECT kl.source_type, a.owner_only,"
          + " CASE WHEN kl.schedule_enabled AND kl.erasure_requested_at IS NULL"
          + " THEN kl.schedule_cron END,"
          + " a.created_at, max(j.completed_at)"
          + " FROM knowledge_libraries kl JOIN assets a ON a.id = kl.id"
          + " LEFT JOIN indexing_jobs j ON j.library_id = kl.id AND j.status = 'COMPLETED'"
          + " GROUP BY kl.id, a.id";

  private final JdbcTemplate jdbc;
  private final EmbeddingReindexService reindex;
  private final Clock clock;
  private final MultiGauge lastSuccess;
  private final MultiGauge overdue;
  private final AtomicReference<Double> reindexRemaining = new AtomicReference<>(0.0);
  private final AtomicBoolean failing = new AtomicBoolean();
  private volatile Set<String> lastSuccessConnectors = Set.of();
  private volatile Set<String> overdueConnectors = Set.of();

  @Autowired
  public IndexingStateMetrics(
      DataSource dataSource,
      MeterRegistry meterRegistry,
      EmbeddingReindexService reindex,
      Clock clock,
      @Qualifier("indexingTaskExecutor") TaskExecutor indexingTaskExecutor) {
    this(reads(dataSource), meterRegistry, reindex, clock, indexingTaskExecutor);
  }

  IndexingStateMetrics(
      JdbcTemplate jdbc,
      MeterRegistry meterRegistry,
      EmbeddingReindexService reindex,
      Clock clock,
      TaskExecutor indexingTaskExecutor) {
    this.jdbc = jdbc;
    this.reindex = reindex;
    this.clock = clock;
    this.lastSuccess =
        MultiGauge.builder("opaa.indexing.run.last_success")
            .baseUnit("seconds")
            .description("Unix time of the latest successful indexing run per connector type")
            .register(meterRegistry);
    this.overdue =
        MultiGauge.builder("opaa.indexing.libraries.overdue")
            .description("Scheduled libraries without a successful run for twice their interval")
            .register(meterRegistry);
    Gauge.builder("opaa.indexing.reindex.remaining", reindexRemaining, AtomicReference::get)
        .description("Chunks a change of the embedding model still has to embed")
        .register(meterRegistry);
    if (indexingTaskExecutor instanceof ThreadPoolTaskExecutor pool) {
      Gauge.builder("opaa.indexing.runs.active", pool, ThreadPoolTaskExecutor::getActiveCount)
          .description("Indexing runs this instance is executing")
          .register(meterRegistry);
      Gauge.builder("opaa.indexing.runs.queued", pool, ThreadPoolTaskExecutor::getQueueSize)
          .description("Indexing runs waiting for a thread of this instance")
          .register(meterRegistry);
    }
  }

  /**
   * Reads both connector gauges anew in one query. Runs on every instance without a lock: it only
   * reads, and each instance reports its own gauges
   * (ScheduledRunLockStructureTest#RUNS_ON_EVERY_INSTANCE). A failed read reports NaN under the
   * connectors of the last one and warns once until a read succeeds again.
   */
  @Scheduled(initialDelay = 0, fixedDelayString = "PT1M")
  public void refresh() {
    try {
      Instant now = Instant.now(clock);
      Map<String, Double> lastSuccessByConnector = new TreeMap<>();
      Map<String, Integer> overdueByConnector = new TreeMap<>();
      jdbc.query(
          LIBRARIES_SQL,
          row -> {
            String connector =
                row.getBoolean(2)
                    ? IndexingMetrics.PRIVATE
                    : IndexingMetrics.connector(row.getString(1));
            Timestamp success = row.getTimestamp(5);
            if (success != null) {
              lastSuccessByConnector.merge(
                  connector, success.toInstant().toEpochMilli() / 1000.0, Math::max);
            }
            Duration interval = row.getString(3) == null ? null : intervalOf(row.getString(3));
            if (interval != null) {
              Instant since = (success != null ? success : row.getTimestamp(4)).toInstant();
              boolean late = since.plus(interval.multipliedBy(OVERDUE_FACTOR)).isBefore(now);
              overdueByConnector.merge(connector, late ? 1 : 0, Integer::sum);
            }
          });
      lastSuccess.register(rows(lastSuccessByConnector), true);
      overdue.register(rows(overdueByConnector), true);
      lastSuccessConnectors = Set.copyOf(lastSuccessByConnector.keySet());
      overdueConnectors = Set.copyOf(overdueByConnector.keySet());
      if (failing.compareAndSet(true, false)) {
        log.info("Indexing state metrics are read again");
      }
    } catch (RuntimeException e) {
      lastSuccess.register(unknown(lastSuccessConnectors), true);
      overdue.register(unknown(overdueConnectors), true);
      if (failing.compareAndSet(false, true)) {
        log.warn("Indexing state metrics could not be read, reporting NaN until they are", e);
      }
    }
  }

  /**
   * Reads the chunks a model change still has to embed. Less often than {@link #refresh}: without a
   * pending change it reads no chunk, with one it counts the whole vector table. NaN while it
   * cannot be read.
   */
  @Scheduled(initialDelay = 0, fixedDelayString = "PT5M")
  public void refreshReindex() {
    try {
      reindexRemaining.set((double) reindex.remainingChunks());
    } catch (RuntimeException e) {
      reindexRemaining.set(Double.NaN);
      log.debug("The re-index backlog could not be read, reporting NaN", e);
    }
  }

  /** NaN under every {@code connector} of the last read: "unknown", not a stale or zero value. */
  private static List<MultiGauge.Row<?>> unknown(Set<String> connectors) {
    return connectors.stream()
        .<MultiGauge.Row<?>>map(
            connector -> MultiGauge.Row.of(Tags.of("connector", connector), Double.NaN))
        .toList();
  }

  private static List<MultiGauge.Row<?>> rows(Map<String, ? extends Number> byConnector) {
    return byConnector.entrySet().stream()
        .<MultiGauge.Row<?>>map(
            entry -> MultiGauge.Row.of(Tags.of("connector", entry.getKey()), entry.getValue()))
        .toList();
  }

  /** The interval of a stored schedule, {@code null} for none. */
  static Duration intervalOf(String cron) {
    ScheduleFrequency frequency = LibraryScheduleCodec.parse(cron).frequency();
    return switch (frequency) {
      case HOURLY -> Duration.ofHours(1);
      case DAILY -> Duration.ofDays(1);
      case WEEKLY -> Duration.ofDays(7);
      case DISABLED -> null;
    };
  }

  /** A template of its own: the query timeout would otherwise bound every other reader as well. */
  static JdbcTemplate reads(DataSource dataSource) {
    JdbcTemplate reads = new JdbcTemplate(dataSource);
    reads.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    return reads;
  }
}
