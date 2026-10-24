package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.test.OpaaIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link AuditStorageMetrics} against the baseline's real partitions and retention row: the
 * baseline creates partitions from three months back through 16 years forward and seeds 36 months
 * with a last run in the month of the migration.
 */
@OpaaIntegrationTest
class AuditStorageMetricsIntegrationTest {

  private static final double DAY = 86_400;

  @Autowired private MeterRegistry meterRegistry;

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
    assertThat(gauge("opaa.audit.retention.period")).isEqualTo(36);
  }

  @Test
  void theLastRunLiesInThePastMonthOrTwo() {
    assertThat(gauge("opaa.audit.retention.last.run.age")).isBetween(0.0, 62 * DAY);
  }

  private double gauge(String name) {
    return meterRegistry.get(name).gauge().value();
  }
}
