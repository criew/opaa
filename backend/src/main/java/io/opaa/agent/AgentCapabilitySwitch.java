package io.opaa.agent;

import io.opaa.api.types.Capability;
import io.opaa.permission.RuntimeCapabilitySwitch;
import org.springframework.stereotype.Component;

/** {@link Capability#CREATE_AGENT} is offered only while agents are switched on. */
@Component
class AgentCapabilitySwitch implements RuntimeCapabilitySwitch {

  private final AgentSwitch agentSwitch;

  AgentCapabilitySwitch(AgentSwitch agentSwitch) {
    this.agentSwitch = agentSwitch;
  }

  @Override
  public Capability capability() {
    return Capability.CREATE_AGENT;
  }

  @Override
  public boolean offered() {
    return agentSwitch.isEnabled();
  }
}
