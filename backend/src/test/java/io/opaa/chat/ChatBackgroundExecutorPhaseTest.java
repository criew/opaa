package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.config.ShutdownLifecycleConfiguration;
import io.opaa.observability.ChatMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Both chat background pools declare the shutdown phase of work that has no recovery (#1710): a
 * title or a condensation interrupted by a stop is never generated again, so the stop gives a
 * running call a short window instead of interrupting it at once like an indexing run, which does
 * recover.
 */
class ChatBackgroundExecutorPhaseTest {

  private final ChatConfiguration configuration = new ChatConfiguration();

  @Test
  void theTitlePoolStopsInTheUnrecoverableBackgroundPhase() {
    ThreadPoolTaskExecutor executor =
        (ThreadPoolTaskExecutor) configuration.chatTitleTaskExecutor();

    assertThat(executor.getPhase())
        .isEqualTo(ShutdownLifecycleConfiguration.UNRECOVERABLE_BACKGROUND_PHASE);
  }

  @Test
  void theNotePoolStopsInTheUnrecoverableBackgroundPhase() {
    ThreadPoolTaskExecutor executor =
        (ThreadPoolTaskExecutor)
            configuration.chatNoteTaskExecutor(new ChatMetrics(new SimpleMeterRegistry()));

    assertThat(executor.getPhase())
        .isEqualTo(ShutdownLifecycleConfiguration.UNRECOVERABLE_BACKGROUND_PHASE);
  }
}
