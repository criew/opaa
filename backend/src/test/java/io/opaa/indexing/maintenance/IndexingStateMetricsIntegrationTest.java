package io.opaa.indexing.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.registry.SourceConnectorRegistry;
import io.opaa.observability.IndexingMetrics;
import io.opaa.organization.Organization;
import io.opaa.test.BareLibraries;
import io.opaa.test.OpaaIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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
  @Autowired private IndexingJobService indexingJobService;
  @Autowired private SourceConnectorRegistry connectorRegistry;

  @Autowired
  @Qualifier("indexingTaskExecutor")
  private TaskExecutor indexingTaskExecutor;

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

  /**
   * Runs of an owner-only library count as {@code private}, never under their source type: no
   * series traces one person's library. Its run lies ahead of any other private library's.
   */
  @Test
  void anOwnerOnlyLibraryCountsAsPrivate() {
    UUID ownersOwn = UUID.randomUUID();
    BareLibraries.create(jdbcTemplate, ownersOwn, true);
    try {
      jdbcTemplate.update(
          "UPDATE knowledge_libraries SET source_type = ?, schedule_enabled = true,"
              + " schedule_cron = ? WHERE id = ?",
          CONNECTOR_TYPE,
          DAILY_AT_THREE,
          ownersOwn);
      Instant ahead = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofDays(365));
      run(ownersOwn, "COMPLETED", ahead);
      run(dead, "COMPLETED", Instant.now().minus(Duration.ofDays(3)));
      run(alive, "COMPLETED", Instant.now().minus(Duration.ofMinutes(5)));

      metrics.refresh();

      assertThat(
              meterRegistry
                  .get("opaa.indexing.run.last_success")
                  .tag("connector", IndexingMetrics.PRIVATE)
                  .gauge()
                  .value())
          .isEqualTo(ahead.getEpochSecond());
      assertThat(gauge("opaa.indexing.run.last_success"))
          .as("the private run never shows under its source type")
          .isLessThan(ahead.getEpochSecond());
      assertThat(gauge("opaa.indexing.libraries.overdue")).isEqualTo(1.0);
    } finally {
      jdbcTemplate.update("DELETE FROM indexing_jobs WHERE library_id = ?", ownersOwn);
      BareLibraries.remove(jdbcTemplate, ownersOwn);
    }
  }

  /**
   * Every registered source type reports its document series from the context start, before its
   * first document: OpaaIndexingStalled needs a series to see no increase on a fresh pod.
   */
  @Test
  void everyRegisteredSourceTypeReportsItsDocumentSeriesFromTheStart() {
    List<String> connectors =
        connectorRegistry.descriptors().stream()
            .map(descriptor -> descriptor.type().key().toLowerCase(Locale.ROOT))
            .toList();

    assertThat(connectors).contains("upload");
    for (String connector : connectors) {
      for (String result : List.of("processed", "skipped", "failed")) {
        assertThat(
                meterRegistry
                    .find("opaa.indexing.documents")
                    .tags("connector", connector, "result", result)
                    .counter())
            .as("%s/%s", connector, result)
            .isNotNull();
      }
    }
  }

  @Test
  void withoutAPendingModelChangeNothingIsLeftToReindex() {
    metrics.refreshReindex();

    assertThat(meterRegistry.get("opaa.indexing.reindex.remaining").gauge().value()).isZero();
  }

  /** Every thread of the pool busy and one more run waiting: both gauges say so. */
  @Test
  void theRunsThePoolExecutesAndHoldsQueuedAreReported() throws InterruptedException {
    ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) indexingTaskExecutor;
    int threads = pool.getCorePoolSize();
    CountDownLatch release = new CountDownLatch(1);
    try {
      for (int i = 0; i <= threads; i++) {
        pool.execute(
            () -> {
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
      }
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                assertThat(meterRegistry.get("opaa.indexing.runs.active").gauge().value())
                    .isEqualTo(threads);
                assertThat(meterRegistry.get("opaa.indexing.runs.queued").gauge().value())
                    .isEqualTo(1.0);
              });
    } finally {
      release.countDown();
    }
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(meterRegistry.get("opaa.indexing.runs.active").gauge().value()).isZero();
              assertThat(meterRegistry.get("opaa.indexing.runs.queued").gauge().value()).isZero();
            });
  }

  /**
   * The recovery fails a stale run in one statement and finds it again in Postgres by the end time
   * and message it wrote: the run counts once, as {@code abandoned}.
   */
  @Test
  void aRunTheRecoveryFailsCountsAsAbandoned() {
    Instant stale = Instant.now().minus(Duration.ofDays(2));
    jdbcTemplate.update(
        "INSERT INTO indexing_jobs (id, status, started_at, last_progress_at, organization_id,"
            + " library_id, run_mode) VALUES (?, 'RUNNING', ?, ?, ?, ?, 'INCREMENTAL')",
        UUID.randomUUID(),
        Timestamp.from(stale),
        Timestamp.from(stale),
        Organization.DEFAULT_ID,
        alive);
    double before = abandoned();

    indexingJobService.recoverStaleJobs(Duration.ofHours(1));

    assertThat(abandoned()).isEqualTo(before + 1);
  }

  private double abandoned() {
    Counter counter =
        meterRegistry
            .find("opaa.indexing.runs")
            .tags(
                "connector",
                CONNECTOR,
                "mode",
                "delta",
                "result",
                "failed",
                "failure_category",
                "abandoned")
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private static org.assertj.core.data.Offset<Double> within() {
    return org.assertj.core.data.Offset.offset(0.001);
  }
}
