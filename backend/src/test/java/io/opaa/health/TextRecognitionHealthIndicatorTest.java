package io.opaa.health;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.llm.OptionalRoleState;
import io.opaa.llm.OptionalRoleStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/** A switched-off role is healthy; switched on but unusable is down, in its own group only. */
class TextRecognitionHealthIndicatorTest {

  private static Health healthOf(OptionalRoleStatus status) {
    return new TextRecognitionHealthIndicator(() -> status).health();
  }

  @Test
  void aSwitchedOffRoleIsUp() {
    assertThat(healthOf(OptionalRoleStatus.disabled()).getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void aReadyRoleIsUpWithItsEndpoint() {
    Health health =
        healthOf(
            new OptionalRoleStatus(
                OptionalRoleState.READY, "http://docling:5001", null, null, false, false));

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("baseUrl", "http://docling:5001")
        .doesNotContainKey("modelIdentifier");
  }

  @Test
  void anUnreachableRoleIsDown() {
    Health health =
        healthOf(
            new OptionalRoleStatus(
                OptionalRoleState.UNREACHABLE,
                "http://docling:5001",
                null,
                "connection refused",
                false,
                false));

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("diagnostic", "connection refused");
  }

  @Test
  void itNamesItsOwnGroup() {
    assertThat(new TextRecognitionHealthIndicator(OptionalRoleStatus::disabled).healthGroup())
        .isEqualTo("text-recognition-model");
  }
}
