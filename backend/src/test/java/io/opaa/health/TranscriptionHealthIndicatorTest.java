package io.opaa.health;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.llm.TranscriptionRoleState;
import io.opaa.llm.TranscriptionRoleStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/** A switched-off role is healthy; switched on but unusable is down, in its own group only. */
class TranscriptionHealthIndicatorTest {

  private static Health healthOf(TranscriptionRoleStatus status) {
    return new TranscriptionHealthIndicator(() -> status).health();
  }

  @Test
  void aSwitchedOffRoleIsUp() {
    assertThat(healthOf(TranscriptionRoleStatus.disabled()).getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void aReadyRoleIsUpWithItsEndpoint() {
    Health health =
        healthOf(
            new TranscriptionRoleStatus(
                TranscriptionRoleState.READY, "http://stt:8000/v1", "whisper", null, false, false));

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("baseUrl", "http://stt:8000/v1");
  }

  @Test
  void anUnreachableRoleIsDown() {
    Health health =
        healthOf(
            new TranscriptionRoleStatus(
                TranscriptionRoleState.UNREACHABLE,
                "http://stt:8000/v1",
                "whisper",
                "connection refused",
                false,
                false));

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("diagnostic", "connection refused");
  }
}
