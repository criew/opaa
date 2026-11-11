package io.opaa.agent;

import io.opaa.asset.ResponsibleUnit;

/**
 * The complete new state of an agent: name, purpose and what a run uses.
 *
 * @param responsible the responsible unit, {@code null} to keep the stored one
 */
public record AgentUpdate(
    String name,
    String description,
    AgentConfiguration configuration,
    ResponsibleUnit responsible) {

  public AgentUpdate(String name, String description, AgentConfiguration configuration) {
    this(name, description, configuration, null);
  }
}
