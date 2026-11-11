package io.opaa.query.toolloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AgentLimitsTest {

  private static final AgentLimits SYSTEM = new AgentLimits(5, 3, Duration.ofSeconds(120), 60_000);

  @Test
  void anAgentLimitLowersTheSystemLimit() {
    AgentLimits effective =
        AgentLimits.effective(SYSTEM, new AgentLimits(2, 1, Duration.ofSeconds(30), 1_000));

    assertThat(effective).isEqualTo(new AgentLimits(2, 1, Duration.ofSeconds(30), 1_000));
  }

  @Test
  void anAgentLimitNeverRaisesTheSystemLimit() {
    AgentLimits effective =
        AgentLimits.effective(SYSTEM, new AgentLimits(50, 30, Duration.ofHours(1), 1_000_000));

    assertThat(effective).isEqualTo(SYSTEM);
  }

  @Test
  void anUnsetComponentFallsBackToTheSystemLimit() {
    assertThat(AgentLimits.effective(SYSTEM, AgentLimits.SYSTEM)).isEqualTo(SYSTEM);
    assertThat(AgentLimits.effective(SYSTEM, new AgentLimits(null, 2, null, null)))
        .isEqualTo(new AgentLimits(5, 2, Duration.ofSeconds(120), 60_000));
  }

  @Test
  void anAgentWithoutLimitsKeepsTheDefaultWhenTheSystemLimitsAreRaised() {
    AgentLimits raised = new AgentLimits(12, 8, Duration.ofSeconds(240), 60_000);

    assertThat(AgentLimits.effective(raised, AgentLimits.SYSTEM))
        .isEqualTo(new AgentLimits(5, 3, Duration.ofSeconds(120), 60_000));
    assertThat(AgentLimits.effective(raised, null))
        .isEqualTo(new AgentLimits(5, 3, Duration.ofSeconds(120), 60_000));
  }

  @Test
  void anExplicitLimitMayGoAboveTheDefaultUpToTheSystemLimit() {
    AgentLimits raised = new AgentLimits(12, 8, Duration.ofSeconds(240), 60_000);

    assertThat(AgentLimits.effective(raised, new AgentLimits(12, 8, Duration.ofSeconds(240), null)))
        .isEqualTo(raised);
    assertThat(AgentLimits.effective(raised, new AgentLimits(99, 99, Duration.ofHours(1), null)))
        .isEqualTo(raised);
  }

  @Test
  void aSystemLimitBelowTheDefaultStillWins() {
    AgentLimits low = new AgentLimits(2, 1, Duration.ofSeconds(30), 1_000);

    assertThat(AgentLimits.effective(low, AgentLimits.SYSTEM)).isEqualTo(low);
  }

  @Test
  void anAgentWithoutAVolumeOfItsOwnKeepsTheFormerVolumeWhenTheSystemRaisesIt() {
    AgentLimits raised = new AgentLimits(12, 8, Duration.ofSeconds(240), 200_000);

    assertThat(AgentLimits.effective(raised, AgentLimits.SYSTEM).maxCollectedChars())
        .isEqualTo(60_000);
    assertThat(
            AgentLimits.effective(raised, new AgentLimits(null, null, null, 150_000))
                .maxCollectedChars())
        .isEqualTo(150_000);
    assertThat(
            AgentLimits.effective(raised, new AgentLimits(null, null, null, 900_000))
                .maxCollectedChars())
        .isEqualTo(200_000);
  }
}
