package io.opaa.query.toolcheck;

import io.opaa.agent.AgentSwitch;
import org.springframework.stereotype.Component;

/**
 * Whether tool checks are offered: only while agents are switched on ({@link AgentSwitch}). Asked
 * on every request, so a change of the switch holds on every instance from the next request on.
 */
@Component
public class ToolCheckGate {

  private final AgentSwitch agentSwitch;

  ToolCheckGate(AgentSwitch agentSwitch) {
    this.agentSwitch = agentSwitch;
  }

  /** Whether the installation offers tool checks right now. */
  public boolean open() {
    return agentSwitch.isEnabled();
  }
}
