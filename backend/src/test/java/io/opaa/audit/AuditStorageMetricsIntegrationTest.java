package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.test.OpaaIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link AuditStorageMetrics} against the baseline's real partitions and retention row: the
 * baseline creates partitions from three months back through 16 years forward and a last run in the
 * month of the migration. Each test reads the values anew before it looks at a gauge.
 */
@OpaaIntegrationTest
class AuditStorageMetricsIntegrationTest {

  private static final double DAY = 86_400;

  @Autowired private MeterRegistry meterRegistry;
  @Autowired private AuditStorageMetrics metrics;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void readAnew() {
    metrics.refresh();
  }

  @Test
  void theSizeCoversThePartitionsAndIndexes() {
    assertThat(gauge("opaa.audit.log.size")).isPositive();
  }

  @Test
  void theOldestPartitionStartedBeforeNowAndWithinTheRetentionPeriod() {
    assertThat(gauge("opaa.audit.log.oldest.partition.age")).isBetween(DAY, 40 * 31 * DAY);
  }

  @Test
  void theHorizonReachesYearsAhead() {
    assertThat(gauge("opaa.audit.log.horizon")).isGreaterThan(10 * 365 * DAY);
  }

  @Test
  void theRetentionPeriodIsTheConfiguredOne() {
    Integer configured =
        jdbcTemplate.queryForObject(
            "SELECT retention_months FROM audit_retention_settings WHERE id = 1", Integer.class);

    assertThat(gauge("opaa.audit.retention.period")).isEqualTo(configured.doubleValue());
  }

  /**
   * That a scrape between two reads shows the earlier value is {@code AuditStorageMetricsTest}'s
   * part; here the scheduled read of the context may run at any moment.
   */
  @Test
  void aRefreshReadsAChangedValue() {
    double read = gauge("opaa.audit.retention.period");
    jdbcTemplate.update(
        "UPDATE audit_retention_settings SET retention_months = retention_months + 1 WHERE id = 1");
    try {
      metrics.refresh();
      assertThat(gauge("opaa.audit.retention.period")).isEqualTo(read + 1);
    } finally {
      jdbcTemplate.update(
          "UPDATE audit_retention_settings SET retention_months = retention_months - 1"
              + " WHERE id = 1");
    }
  }

  @Test
  void theLastRunLiesInThePastMonthOrTwo() {
    assertThat(gauge("opaa.audit.retention.last.run.age")).isBetween(0.0, 62 * DAY);
  }

  private double gauge(String name) {
    return meterRegistry.get(name).gauge().value();
  }
}
