package io.opaa.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.query.toolloop.AgentToolSwitches;
import io.opaa.test.OpaaIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The tool loop reads the installation's tool switches through {@link AgentToolSwitches}; without a
 * bean of that type every tool counts as switched off, so the agent settings must provide it.
 */
@OpaaIntegrationTest
class AgentToolSwitchesWiringIntegrationTest {

  @Autowired private ObjectProvider<AgentToolSwitches> toolSwitches;
  @Autowired private AgentSettingsService settingsService;

  @Test
  void agentSettingsAreTheToolSwitchesOfTheToolLoop() {
    assertThat(toolSwitches.getIfAvailable()).isSameAs(settingsService);
  }
}
