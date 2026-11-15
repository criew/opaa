package io.opaa.health;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.llm.OptionalRoleState;
import io.opaa.llm.OptionalRoleStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/** A switched-off role is healthy; switched on but unusable is down, in its own group only. */
class RerankHealthIndicatorTest {

  private static Health healthOf(OptionalRoleStatus status) {
    return new RerankHealthIndicator(() -> status).health();
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
                OptionalRoleState.READY, "http://rerank:8000", "bge-reranker", null, false, false));

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("baseUrl", "http://rerank:8000")
        .containsEntry("modelIdentifier", "bge-reranker");
  }

  @Test
  void anUnconfiguredRoleIsDown() {
    Health health =
        healthOf(
            new OptionalRoleStatus(OptionalRoleState.UNCONFIGURED, null, null, null, false, false));

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("state", "UNCONFIGURED");
  }

  @Test
  void anUnreachableRoleIsDown() {
    Health health =
        healthOf(
            new OptionalRoleStatus(
                OptionalRoleState.UNREACHABLE,
                "http://rerank:8000",
                "bge-reranker",
                "connection refused",
                false,
                false));

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("diagnostic", "connection refused");
  }

  @Test
  void itNamesItsOwnGroup() {
    assertThat(new RerankHealthIndicator(OptionalRoleStatus::disabled).healthGroup())
        .isEqualTo("rerank-model");
  }
}
