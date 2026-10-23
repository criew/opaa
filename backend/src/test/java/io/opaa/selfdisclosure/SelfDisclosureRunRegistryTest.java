package io.opaa.selfdisclosure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.selfdisclosure.SelfDisclosureAbort.AbortCause;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The watchdog ends a running export by time, not by its output: past the maximum duration and at
 * the shutdown of the instance, also while the exporter writes nothing.
 */
class SelfDisclosureRunRegistryTest {

  private final SelfDisclosureRunRegistry registry =
      new SelfDisclosureRunRegistry(new SelfDisclosureProperties(1, null));

  @AfterEach
  void shutdownWatchdog() {
    registry.shutdownWatchdog();
  }

  @Test
  void anExportPastItsDeadlineIsToldToEndWithoutWritingAnything() throws Exception {
    registry.start();
    SelfDisclosureRunControl control =
        registry.watch(System.nanoTime() + Duration.ofMillis(200).toNanos(), () -> true);

    Thread.sleep(2500);

    assertThatThrownBy(control::check)
        .isInstanceOfSatisfying(
            SelfDisclosureAbort.class,
            abort -> assertThat(abort.abortCause()).isEqualTo(AbortCause.TIMEOUT));
    registry.unwatch(control);
  }

  @Test
  void theShutdownEndsEveryRunningExportAndRefusesNewOnes() {
    registry.start();
    SelfDisclosureRunControl control =
        registry.watch(System.nanoTime() + Duration.ofHours(1).toNanos(), () -> true);
    control.check();

    registry.stop();

    assertThatThrownBy(control::check)
        .isInstanceOfSatisfying(
            SelfDisclosureAbort.class,
            abort -> assertThat(abort.abortCause()).isEqualTo(AbortCause.SHUTDOWN));
    assertThat(registry.tryAcquire()).isFalse();
    registry.unwatch(control);
  }
}
