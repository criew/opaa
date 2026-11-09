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
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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

  static final String LAST_SUCCESS_SQL =
      "SELECT kl.source_type, extract(epoch FROM max(j.completed_at))"
          + " FROM indexing_jobs j JOIN knowledge_libraries kl ON kl.id = j.library_id"
          + " WHERE j.status = 'COMPLETED' GROUP BY kl.source_type";

  /** Every library on a schedule, with its creation and its last successful run. */
  static final String SCHEDULED_SQL =
      "SELECT kl.source_type, kl.schedule_cron, a.created_at,"
          + " (SELECT max(j.completed_at) FROM indexing_jobs j"
          + " WHERE j.library_id = kl.id AND j.status = 'COMPLETED')"
          + " FROM knowledge_libraries kl JOIN assets a ON a.id = kl.id"
          + " WHERE kl.schedule_enabled AND kl.schedule_cron IS NOT NULL"
          + " AND kl.erasure_requested_at IS NULL";

  private final JdbcTemplate jdbc;
  private final EmbeddingReindexService reindex;
  private final Clock clock;
  private final MultiGauge lastSuccess;
  private final MultiGauge overdue;
  private final AtomicLong reindexRemaining = new AtomicLong();
  private final AtomicBoolean failing = new AtomicBoolean();

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
    Gauge.builder("opaa.indexing.reindex.remaining", reindexRemaining, AtomicLong::get)
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
   * Reads every value anew. Runs on every instance without a lock: it only reads, and each instance
   * reports its own gauges (ScheduledRunLockStructureTest#RUNS_ON_EVERY_INSTANCE). A failed read
   * keeps the values of the last one and warns once until a read succeeds again.
   */
  @Scheduled(initialDelay = 0, fixedDelayString = "PT1M")
  public void refresh() {
    try {
      lastSuccess.register(lastSuccessRows(), true);
      overdue.register(overdueRows(), true);
      reindexRemaining.set(reindex.remainingChunks());
      if (failing.compareAndSet(true, false)) {
        log.info("Indexing state metrics are read again");
      }
    } catch (RuntimeException e) {
      if (failing.compareAndSet(false, true)) {
        log.warn("Indexing state metrics could not be read, keeping the last values", e);
      }
    }
  }

  private List<MultiGauge.Row<?>> lastSuccessRows() {
    Map<String, Double> byConnector = new TreeMap<>();
    jdbc.query(
        LAST_SUCCESS_SQL,
        row -> {
          byConnector.put(IndexingMetrics.connector(row.getString(1)), row.getDouble(2));
        });
    return byConnector.entrySet().stream()
        .<MultiGauge.Row<?>>map(
            entry -> MultiGauge.Row.of(Tags.of("connector", entry.getKey()), entry.getValue()))
        .toList();
  }

  /**
   * One row per connector type with a scheduled library: how many of them are overdue. A library
   * that never succeeded counts from its creation.
   */
  private List<MultiGauge.Row<?>> overdueRows() {
    Instant now = Instant.now(clock);
    Map<String, Integer> byConnector = new TreeMap<>();
    jdbc.query(
        SCHEDULED_SQL,
        row -> {
          Duration interval = intervalOf(row.getString(2));
          if (interval == null) {
            return;
          }
          Timestamp success = row.getTimestamp(4);
          Instant since = (success != null ? success : row.getTimestamp(3)).toInstant();
          boolean late = since.plus(interval.multipliedBy(OVERDUE_FACTOR)).isBefore(now);
          byConnector.merge(
              IndexingMetrics.connector(row.getString(1)), late ? 1 : 0, Integer::sum);
        });
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
