package io.opaa.query.toolloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The share of one person in the workspace is at least one place and never all beyond it. */
class AgentToolLoopPropertiesTest {

  @Test
  void aShareOfAtLeastOneAndAtMostTheCapacityIsAccepted() {
    assertThat(properties(2, 1).workspaceCapacityPerPerson()).isEqualTo(1);
    assertThat(properties(2, 2).workspaceCapacityPerPerson()).isEqualTo(2);
  }

  @Test
  void aShareBelowOneIsRefused() {
    assertThatThrownBy(() -> properties(2, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workspaceCapacityPerPerson");
  }

  @Test
  void aShareAboveTheCapacityIsRefused() {
    assertThatThrownBy(() -> properties(2, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workspaceCapacityPerPerson must not exceed workspaceCapacity");
  }

  private static AgentToolLoopProperties properties(int capacity, int perPerson) {
    return new AgentToolLoopProperties(
        5, 3, Duration.ofSeconds(120), 60_000, capacity, perPerson, Duration.ofSeconds(60));
  }
}
