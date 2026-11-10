package io.opaa.instance;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * Every run the application actually scheduled has its {@code opaa.scheduled.*} series from the
 * start, with an expected interval; a run whose bean is switched off - the SIEM export without
 * {@code opaa.siem-export.enabled} - has none; a run that ran here has its last success.
 */
@OpaaIntegrationTest
class ScheduledJobMetricsIntegrationTest {

  @Autowired private MeterRegistry meterRegistry;
  @Autowired private List<ScheduledTaskHolder> holders;

  @Test
  void everyScheduledRunHasItsSeriesAndASwitchedOffOneHasNone() {
    Set<String> scheduledJobs =
        holders.stream()
            .flatMap(holder -> holder.getScheduledTasks().stream())
            .map(task -> String.valueOf(task.getTask().getRunnable()))
            .map(ScheduledJobMetricsIntegrationTest::jobOf)
            .collect(Collectors.toSet());

    assertThat(scheduledJobs)
        .contains("audit-chain", "mail-outbox-worker.poll")
        .hasSizeGreaterThan(20);
    assertThat(jobsOf(ScheduledJobMetrics.EXPECTED_INTERVAL)).isEqualTo(scheduledJobs);
    assertThat(jobsOf(ScheduledJobMetrics.LAST_SUCCESS)).containsAll(scheduledJobs);
    assertThat(jobsOf(ScheduledJobMetrics.FAILURES)).containsAll(scheduledJobs);
    assertThat(jobsOf(ScheduledJobMetrics.LAST_SUCCESS)).doesNotContain("siem-export");
    assertThat(
            meterRegistry
                .get(ScheduledJobMetrics.EXPECTED_INTERVAL)
                .tag("job", "audit-chain")
                .gauge()
                .value())
        .isEqualTo(600.0);
  }

  @Test
  void aRunThatRanOnThisInstanceShowsItsLastSuccess() {
    // runs at the start and without a lock, so it ran on this instance
    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(
                        meterRegistry
                            .get(ScheduledJobMetrics.LAST_SUCCESS)
                            .tag("job", "audit-storage-metrics.refresh")
                            .gauge()
                            .value())
                    .isGreaterThan(0.0));
  }

  private Set<String> jobsOf(String meter) {
    return meterRegistry.find(meter).meters().stream()
        .map(Meter::getId)
        .map(id -> id.getTag("job"))
        .collect(Collectors.toSet());
  }

  private static String jobOf(String description) {
    int dot = description.lastIndexOf('.');
    try {
      return ScheduledJobMetrics.jobName(
          Class.forName(description.substring(0, dot)).getMethod(description.substring(dot + 1)));
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("not a scheduled method: " + description, e);
    }
  }
}
