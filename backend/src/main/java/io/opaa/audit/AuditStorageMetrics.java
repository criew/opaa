package io.opaa.audit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.opaa.observability.PolledGauges;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Size and retention state of the audit log as polled gauges (ADR-0071), read from the system
 * catalog and the retention settings row - never from a row of the log, which keeps this class
 * outside the readers of {@code AuditLogReaderStructureTest}. Every instance reads them every
 * {@link #INTERVAL} (ADR-0053); a value it could not read, also within {@value
 * PolledGauges#QUERY_TIMEOUT_SECONDS} seconds, is {@code NaN}.
 */
@Configuration(proxyBeanMethods = false)
public class AuditStorageMetrics {

  /** How often the values are read anew. */
  static final Duration INTERVAL = Duration.ofMinutes(5);

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

  @Bean
  PolledGauges auditStorageGauges(DataSource dataSource, MeterRegistry meterRegistry) {
    return gauges(PolledGauges.reads(dataSource), meterRegistry);
  }

  /** The gauges, each filled by its own read through {@code jdbc}. */
  static PolledGauges gauges(JdbcTemplate jdbc, MeterRegistry meterRegistry) {
    return new PolledGauges(meterRegistry, INTERVAL)
        .gauge(
            "opaa.audit.log.size",
            Tags.empty(),
            "bytes",
            "Size of all monthly partitions of the audit log, indexes included",
            PolledGauges.sql(jdbc, SIZE_SQL))
        .gauge(
            "opaa.audit.log.oldest.partition.age",
            Tags.empty(),
            "seconds",
            "Time since the start of the oldest monthly partition of the audit log",
            PolledGauges.sql(jdbc, OLDEST_PARTITION_AGE_SQL))
        .gauge(
            "opaa.audit.log.horizon",
            Tags.empty(),
            "seconds",
            "Time until the end of the newest monthly partition; later writes fail",
            PolledGauges.sql(jdbc, HORIZON_SQL))
        .gauge(
            "opaa.audit.retention.period",
            Tags.empty(),
            "months",
            "Configured retention period of the audit log",
            PolledGauges.sql(jdbc, RETENTION_MONTHS_SQL))
        .gauge(
            "opaa.audit.retention.last.run.age",
            Tags.empty(),
            "seconds",
            "Time since the start of the month of the last retention run",
            PolledGauges.sql(jdbc, LAST_RUN_AGE_SQL));
  }
}
