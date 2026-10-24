package io.opaa.audit;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Size and retention state of the audit log as gauges, read from the system catalog and the
 * retention settings row - never from a row of the log, which keeps this class outside the readers
 * of {@code AuditLogReaderStructureTest}. {@link #refresh} reads them on a schedule on every
 * instance (ADR-0053), so a scrape never waits for the database; a value it could not read is
 * {@code NaN}.
 */
@Component
public class AuditStorageMetrics {

  private static final Logger log = LoggerFactory.getLogger(AuditStorageMetrics.class);

  /** The monthly partitions of the log, by their name {@code audit_log_YYYY_MM}. */
  private static final String PARTITIONS =
      " FROM pg_catalog.pg_inherits i JOIN pg_catalog.pg_class c ON c.oid = i.inhrelid"
          + " WHERE i.inhparent = to_regclass('audit_log')"
          + " AND c.relname ~ '^audit_log_[0-9]{4}_[0-9]{2}$'";

  private static final String PARTITION_MONTH = "to_date(right(c.relname, 7), 'YYYY_MM')";

  static final String SIZE_SQL =
      "SELECT coalesce(sum(pg_catalog.pg_total_relation_size(c.oid)), 0)" + PARTITIONS;

  static final String OLDEST_PARTITION_AGE_SQL =
      "SELECT extract(epoch FROM now() - min(" + PARTITION_MONTH + "))" + PARTITIONS;

  static final String HORIZON_SQL =
      "SELECT extract(epoch FROM max("
          + PARTITION_MONTH
          + ") + interval '1 month' - now())"
          + PARTITIONS;

  static final String RETENTION_MONTHS_SQL =
      "SELECT retention_months FROM audit_retention_settings WHERE id = 1";

  static final String LAST_RUN_AGE_SQL =
      "SELECT extract(epoch FROM now() - last_run_month) FROM audit_retention_settings"
          + " WHERE id = 1";

  private final JdbcTemplate jdbc;
  private final List<Reading> readings = new ArrayList<>();

  public AuditStorageMetrics(JdbcTemplate jdbc, MeterRegistry meterRegistry) {
    this.jdbc = jdbc;
    gauge(
        meterRegistry,
        "opaa.audit.log.size",
        "bytes",
        "Size of all monthly partitions of the audit log, indexes included",
        SIZE_SQL);
    gauge(
        meterRegistry,
        "opaa.audit.log.oldest.partition.age",
        "seconds",
        "Time since the start of the oldest monthly partition of the audit log",
        OLDEST_PARTITION_AGE_SQL);
    gauge(
        meterRegistry,
        "opaa.audit.log.horizon",
        "seconds",
        "Time until the end of the newest monthly partition; later writes fail",
        HORIZON_SQL);
    gauge(
        meterRegistry,
        "opaa.audit.retention.period",
        "months",
        "Configured retention period of the audit log",
        RETENTION_MONTHS_SQL);
    gauge(
        meterRegistry,
        "opaa.audit.retention.last.run.age",
        "seconds",
        "Time since the start of the month of the last retention run",
        LAST_RUN_AGE_SQL);
  }

  /**
   * Reads every value anew. Runs on every instance without a lock: it only reads, and each instance
   * reports its own gauges (ScheduledRunLockStructureTest#RUNS_ON_EVERY_INSTANCE).
   */
  @Scheduled(initialDelay = 0, fixedDelayString = "PT5M")
  public void refresh() {
    for (Reading reading : readings) {
      reading.value.set(read(reading));
    }
  }

  private void gauge(
      MeterRegistry meterRegistry, String name, String unit, String description, String sql) {
    Reading reading = new Reading(name, sql);
    readings.add(reading);
    Gauge.builder(name, reading.value, AtomicReference::get)
        .baseUnit(unit)
        .description(description)
        .register(meterRegistry);
  }

  /**
   * The single numeric value of the reading's query, {@code NaN} when it is null or fails. A
   * failure warns once until a read succeeds again.
   */
  private double read(Reading reading) {
    try {
      Double result = jdbc.queryForObject(reading.sql, Double.class);
      if (reading.failing.compareAndSet(true, false)) {
        log.info("Audit storage metric {} is read again", reading.name);
      }
      return result == null ? Double.NaN : result;
    } catch (RuntimeException e) {
      if (reading.failing.compareAndSet(false, true)) {
        log.warn(
            "Audit storage metric {} could not be read and reports NaN: {}",
            reading.name,
            e.getMessage());
      }
      return Double.NaN;
    }
  }

  /** One gauge: its query, its last value and whether its last read failed. */
  private static final class Reading {
    private final String name;
    private final String sql;
    private final AtomicReference<Double> value = new AtomicReference<>(Double.NaN);
    private final AtomicBoolean failing = new AtomicBoolean();

    private Reading(String name, String sql) {
      this.name = name;
      this.sql = sql;
    }
  }
}
