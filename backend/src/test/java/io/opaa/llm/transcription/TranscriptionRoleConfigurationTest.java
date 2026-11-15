package io.opaa.llm.transcription;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.llm.ModelRoleSettingsChangedEvent;
import io.opaa.llm.OptionalRoleStartupCheck;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;

/**
 * The live upstream is probed again after every change of the transcription role, and only then.
 */
class TranscriptionRoleConfigurationTest {

  private final RealtimeTranscriptionRole role = mock(RealtimeTranscriptionRole.class);
  private final TaskScheduler scheduler = mock(TaskScheduler.class);

  private ApplicationContextRunner runner() {
    when(role.operationAllows()).thenReturn(true);
    return new ApplicationContextRunner()
        .withBean(
            OptionalRoleStartupCheck.class,
            () ->
                new TranscriptionRoleConfiguration()
                    .realtimeTranscriptionRoleStartupCheck(role, scheduler, Clock.systemUTC()));
  }

  @Test
  void aTranscriptionChangeSchedulesALiveProbe() {
    runner()
        .run(
            context ->
                context.publishEvent(
                    new ModelRoleSettingsChangedEvent(
                        ModelRoleSettingsChangedEvent.Role.TRANSCRIPTION)));

    verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
  }

  @Test
  void aChangeOfAnotherRoleDoesNotProbeTheLiveUpstream() {
    runner()
        .run(
            context ->
                context.publishEvent(
                    new ModelRoleSettingsChangedEvent(ModelRoleSettingsChangedEvent.Role.RERANK)));

    verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
  }
}
