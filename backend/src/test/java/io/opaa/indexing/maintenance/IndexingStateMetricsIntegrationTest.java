package io.opaa.indexing.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.organization.Organization;
import io.opaa.test.BareLibraries;
import io.opaa.test.OpaaIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The indexing gauges read from the real tables. This class's libraries carry a source type of
 * their own, so its {@code connector} rows count nothing of another class sharing the database.
 */
@OpaaIntegrationTest
class IndexingStateMetricsIntegrationTest {

  private static final String CONNECTOR_TYPE = "GAUGE_PROBE";
  private static final String CONNECTOR = "gauge_probe";
  private static final String DAILY_AT_THREE = "0 0 3 * * *";

  @Autowired private IndexingStateMetrics metrics;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final UUID dead = UUID.randomUUID();
  private final UUID alive = UUID.randomUUID();

  @BeforeEach
  void createScheduledLibraries() {
    for (UUID library : new UUID[] {dead, alive}) {
      BareLibraries.create(jdbcTemplate, library);
      jdbcTemplate.update(
          "UPDATE knowledge_libraries SET source_type = ?, schedule_enabled = true,"
              + " schedule_cron = ? WHERE id = ?",
          CONNECTOR_TYPE,
          DAILY_AT_THREE,
          library);
    }
  }

  @AfterEach
  void removeLibraries() {
    for (UUID library : new UUID[] {dead, alive}) {
      jdbcTemplate.update("DELETE FROM indexing_jobs WHERE library_id = ?", library);
      BareLibraries.remove(jdbcTemplate, library);
    }
  }

  private void run(UUID library, String status, Instant completedAt) {
    jdbcTemplate.update(
        "INSERT INTO indexing_jobs (id, status, started_at, last_progress_at, completed_at,"
            + " organization_id, library_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        status,
        Timestamp.from(completedAt.minus(Duration.ofMinutes(5))),
        Timestamp.from(completedAt),
        Timestamp.from(completedAt),
        Organization.DEFAULT_ID,
        library);
  }

  private double gauge(String name) {
    metrics.refresh();
    return meterRegistry.get(name).tag("connector", CONNECTOR).gauge().value();
  }

  @Test
  void lastSuccessRisesWithASuccessfulRunAndStandsStillAfterAFailedOne() {
    Instant first = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofHours(2));
    run(alive, "COMPLETED", first);
    assertThat(gauge("opaa.indexing.run.last_success")).isEqualTo(first.getEpochSecond());

    run(alive, "FAILED", first.plus(Duration.ofHours(1)));
    assertThat(gauge("opaa.indexing.run.last_success")).isEqualTo(first.getEpochSecond());

    Instant second = first.plus(Duration.ofMinutes(90));
    run(alive, "COMPLETED", second);
    assertThat(gauge("opaa.indexing.run.last_success")).isEqualTo(second.getEpochSecond());
  }

  @Test
  void anOverdueLibraryCountsWhileAnotherOfItsTypeSucceeds() {
    Instant now = Instant.now();
    run(dead, "COMPLETED", now.minus(Duration.ofDays(3)));
    run(dead, "FAILED", now.minus(Duration.ofHours(2)));
    run(alive, "COMPLETED", now.minus(Duration.ofMinutes(5)));

    assertThat(gauge("opaa.indexing.libraries.overdue")).isEqualTo(1.0);
    assertThat(gauge("opaa.indexing.run.last_success"))
        .isEqualTo(now.minus(Duration.ofMinutes(5)).toEpochMilli() / 1000.0, within());
  }

  @Test
  void aLibraryWithoutScheduleIsNeverOverdue() {
    jdbcTemplate.update(
        "UPDATE knowledge_libraries SET schedule_enabled = false, schedule_cron = NULL WHERE id = ?",
        dead);
    run(dead, "COMPLETED", Instant.now().minus(Duration.ofDays(30)));
    run(alive, "COMPLETED", Instant.now());

    assertThat(gauge("opaa.indexing.libraries.overdue")).isZero();
  }

  @Test
  void aScheduledLibraryThatNeverSucceededCountsFromItsCreation() {
    jdbcTemplate.update(
        "UPDATE assets SET created_at = now() - interval '3 days' WHERE id = ?", dead);
    run(alive, "COMPLETED", Instant.now());

    assertThat(gauge("opaa.indexing.libraries.overdue")).isEqualTo(1.0);
  }

  @Test
  void theReindexBacklogAndThePoolOfRunsAreReported() {
    metrics.refresh();

    assertThat(meterRegistry.get("opaa.indexing.reindex.remaining").gauge().value())
        .isGreaterThanOrEqualTo(0.0);
    assertThat(meterRegistry.get("opaa.indexing.runs.active").gauge().value()).isNotNegative();
    assertThat(meterRegistry.get("opaa.indexing.runs.queued").gauge().value()).isNotNegative();
  }

  private static org.assertj.core.data.Offset<Double> within() {
    return org.assertj.core.data.Offset.offset(0.001);
  }
}
