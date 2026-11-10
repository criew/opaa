package io.opaa.instance;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opaa.audit.AuditChainScheduler;
import io.opaa.test.OpaaIntegrationTest;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

/**
 * Every run the application actually scheduled has its {@code opaa.scheduled.*} series from the
 * start, with an expected interval; a run whose bean is switched off - the SIEM export without
 * {@code opaa.siem-export.enabled} - has none. A locked run through the real ShedLock proxy sets
 * its last success only where it got its lock.
 */
@OpaaIntegrationTest
class ScheduledJobMetricsIntegrationTest {

  @Autowired private MeterRegistry meterRegistry;
  @Autowired private List<ScheduledTaskHolder> holders;
  @Autowired private ObservationRegistry observationRegistry;
  @Autowired private AuditChainScheduler auditChainScheduler;
  @Autowired private JdbcTemplate jdbcTemplate;

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
  void aLockedRunThroughTheShedLockProxyCountsOnlyWithItsLock() throws Exception {
    Method chain = AuditChainScheduler.class.getMethod("chain");
    double before = lastSuccess("audit-chain");
    double invoked = System.currentTimeMillis() / 1000.0;
    try {
      jdbcTemplate.update(
          "INSERT INTO shedlock (name, lock_until, locked_at, locked_by)"
              + " VALUES ('audit-chain', timezone('utc', now()) + interval '1 hour',"
              + " timezone('utc', now()), 'elsewhere') ON CONFLICT (name) DO UPDATE"
              + " SET lock_until = excluded.lock_until, locked_by = excluded.locked_by");
      run(chain);
      assertThat(lastSuccess("audit-chain")).isEqualTo(before);

      release();
      run(chain);
      assertThat(lastSuccess("audit-chain")).isGreaterThanOrEqualTo(Math.floor(invoked));
    } finally {
      release();
    }
  }

  /**
   * Lets the lock lapse; the row stays, since the lock provider remembers that it exists and only
   * updates it.
   */
  private void release() {
    jdbcTemplate.update(
        "UPDATE shedlock SET lock_until = timezone('utc', now()) - interval '1 second'"
            + " WHERE name = 'audit-chain'");
  }

  /** Runs the scheduled method on the proxied bean as Spring's scheduler does, observed. */
  private void run(Method method) {
    new ScheduledMethodRunnable(auditChainScheduler, method, null, () -> observationRegistry).run();
  }

  private double lastSuccess(String job) {
    return meterRegistry.get(ScheduledJobMetrics.LAST_SUCCESS).tag("job", job).gauge().value();
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
