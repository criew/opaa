package io.opaa.audit;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Size and retention state of the audit log as gauges, so that a retention run that stopped
 * happening shows up before the storage grows without bound. Every value comes from the system
 * catalog and the retention settings row - never from a row of the log itself, which keeps this
 * class outside the readers {@code AuditLogReaderStructureTest} allows. Ages are measured against
 * the database clock. Each scrape queries afresh; nothing is held in the process, so every instance
 * reports the same values (ADR-0053).
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

  private void gauge(
      MeterRegistry meterRegistry, String name, String unit, String description, String sql) {
    ToDoubleFunction<AuditStorageMetrics> value = metrics -> metrics.query(sql);
    Gauge.builder(name, this, value)
        .baseUnit(unit)
        .description(description)
        .register(meterRegistry);
  }

  /** The single numeric value of {@code sql}, {@code NaN} when it is null or the query fails. */
  double query(String sql) {
    try {
      Double result = jdbc.queryForObject(sql, Double.class);
      return result == null ? Double.NaN : result;
    } catch (RuntimeException e) {
      log.debug("Audit storage metric query failed: {}", e.getMessage());
      return Double.NaN;
    }
  }
}
