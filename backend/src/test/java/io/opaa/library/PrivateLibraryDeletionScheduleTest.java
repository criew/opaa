package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * An erasure walks the object store original by original, so neither tick may run on the
 * application's single shared scheduler thread; both share one thread of their own, which is what
 * keeps the daily run and the pending run from overlapping.
 */
class PrivateLibraryDeletionScheduleTest {

  @Test
  void everyTickRunsOnTheDeletionSchedulerOnly() {
    List<Method> ticks =
        Arrays.stream(PrivateLibraryDeletionSchedule.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(Scheduled.class))
            .toList();

    assertThat(ticks).hasSize(2);
    assertThat(ticks)
        .allSatisfy(
            tick ->
                assertThat(tick.getAnnotation(Scheduled.class).scheduler())
                    .as("tick %s", tick.getName())
                    .isEqualTo(PrivateLibraryDeletionSchedule.SCHEDULER));
  }

  @Test
  void theDeletionSchedulerHasExactlyOneThread() {
    ThreadPoolTaskScheduler scheduler =
        (ThreadPoolTaskScheduler) PrivateLibraryDeletionSchedule.privateLibraryDeletionScheduler();

    assertThat(scheduler.getPoolSize()).isEqualTo(1);
    assertThat(scheduler.getThreadNamePrefix()).isEqualTo("private-library-deletion-");
  }
}
