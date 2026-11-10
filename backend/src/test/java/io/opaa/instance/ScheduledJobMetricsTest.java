package io.opaa.instance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opaa.test.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

/**
 * {@link ScheduledJobMetrics} on the observation Spring opens around a scheduled run: a failing run
 * counts a failure and leaves the last success alone, a successful one sets it, and a locked run
 * whose lock this instance did not get leaves both alone. Until a run succeeds its last success is
 * the start of the instance.
 */
class ScheduledJobMetricsTest {

  private static final Instant STARTED = Instant.parse("2026-11-10T08:00:00Z");
  private static final Instant NOW = STARTED.plus(Duration.ofHours(1));

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final ObservationRegistry observations = ObservationRegistry.create();
  private final Jobs jobs = new Jobs();
  private final MutableClock clock = new MutableClock(STARTED);

  ScheduledJobMetricsTest() {
    observations
        .observationConfig()
        .observationHandler(
            new ScheduledJobMetrics(
                meters,
                clock,
                new DefaultListableBeanFactory()
                    .getBeanProvider(
                        org.springframework.scheduling.config.ScheduledTaskHolder.class)));
  }

  @AfterEach
  void forgetLocks() {
    ScheduledRunLockAcquisitions.clear();
  }

  @Test
  void aFailingRunCountsAFailureAndLeavesTheLastSuccessAlone() throws Exception {
    clock.advance(Duration.between(STARTED, NOW));
    run("succeedsThenFails");
    clock.advance(Duration.ofMinutes(5));
    jobs.fail = true;

    assertThatThrownBy(() -> run("succeedsThenFails")).isInstanceOf(IllegalStateException.class);

    assertThat(failures("jobs.succeeds-then-fails")).isEqualTo(1.0);
    assertThat(lastSuccess("jobs.succeeds-then-fails")).isEqualTo(NOW.getEpochSecond());
  }

  @Test
  void aRunThatNeverSucceededHasTheStartOfTheInstanceAsItsLastSuccess() throws Exception {
    clock.advance(Duration.between(STARTED, NOW));
    jobs.fail = true;

    assertThatThrownBy(() -> run("succeedsThenFails")).isInstanceOf(IllegalStateException.class);

    assertThat(lastSuccess("jobs.succeeds-then-fails")).isEqualTo(STARTED.getEpochSecond());
    assertThat(failures("jobs.succeeds-then-fails")).isEqualTo(1.0);
  }

  @Test
  void aLockedRunCountsOnlyWhereItGotTheLock() throws Exception {
    clock.advance(Duration.between(STARTED, NOW));
    run("locked");
    assertThat(lastSuccess("nightly-test-run")).isEqualTo(STARTED.getEpochSecond());
    assertThat(failures("nightly-test-run")).isZero();

    jobs.lockGranted = true;
    run("locked");

    assertThat(lastSuccess("nightly-test-run")).isEqualTo(NOW.getEpochSecond());
  }

  @Test
  void aJobIsNamedByItsLockOrByClassAndMethod() throws Exception {
    assertThat(ScheduledJobMetrics.jobName(Jobs.class.getMethod("locked")))
        .isEqualTo("nightly-test-run");
    assertThat(ScheduledJobMetrics.jobName(Jobs.class.getMethod("succeedsThenFails")))
        .isEqualTo("jobs.succeeds-then-fails");
  }

  private void run(String method) throws NoSuchMethodException {
    new ScheduledMethodRunnable(jobs, Jobs.class.getMethod(method), null, () -> observations).run();
  }

  private double lastSuccess(String job) {
    return meters.get(ScheduledJobMetrics.LAST_SUCCESS).tag("job", job).gauge().value();
  }

  private double failures(String job) {
    return meters.get(ScheduledJobMetrics.FAILURES).tag("job", job).counter().count();
  }

  /** Stands in for scheduled beans; the locked run asks the lock provider like ShedLock does. */
  public static class Jobs {
    boolean fail;
    boolean lockGranted;

    private final LockProvider provider =
        ScheduledRunLockAcquisitions.recording(
            configuration -> lockGranted ? Optional.of(() -> {}) : Optional.empty());

    public void succeedsThenFails() {
      if (fail) {
        throw new IllegalStateException("run failed");
      }
    }

    @SchedulerLock(name = "nightly-test-run")
    public void locked() {
      provider.lock(
          new LockConfiguration(
              Instant.now(), "nightly-test-run", Duration.ofMinutes(1), Duration.ZERO));
    }
  }
}
