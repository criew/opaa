package io.opaa.agent;

import io.opaa.api.types.Capability;
import io.opaa.permission.RuntimeCapabilitySwitch;
import org.springframework.stereotype.Component;

/** {@link Capability#VIEW_AGENT_DIRECTORY} is offered only while agents are switched on. */
@Component
class AgentDirectoryCapabilitySwitch implements RuntimeCapabilitySwitch {

  private final AgentSwitch agentSwitch;

  AgentDirectoryCapabilitySwitch(AgentSwitch agentSwitch) {
    this.agentSwitch = agentSwitch;
  }

  @Override
  public Capability capability() {
    return Capability.VIEW_AGENT_DIRECTORY;
  }

  @Override
  public boolean offered() {
    return agentSwitch.isEnabled();
  }
}
