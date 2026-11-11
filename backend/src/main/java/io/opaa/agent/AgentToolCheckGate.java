package io.opaa.agent;

import io.opaa.query.toolcheck.ToolCheckGate;
import org.springframework.stereotype.Component;

/** Offers the tool check of a chat model exactly while agents are switched on. */
@Component
class AgentToolCheckGate implements ToolCheckGate {

  private final AgentSwitch agentSwitch;

  AgentToolCheckGate(AgentSwitch agentSwitch) {
    this.agentSwitch = agentSwitch;
  }

  @Override
  public boolean open() {
    return agentSwitch.isEnabled();
  }
}
