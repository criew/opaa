package io.opaa.agent;

import io.opaa.api.types.AgentPlanStep;
import java.util.Set;
import java.util.UUID;

/**
 * What a run of the agent uses, besides its name and purpose.
 *
 * @param libraryIds the assigned libraries; empty narrows nothing, the run then searches whatever
 *     the calling person may read. A run never reads beyond the caller's rights (ADR-0070,
 *     Entscheidung 3).
 * @param toolNames the tools the agent may call
 */
public record AgentConfiguration(
    String instruction,
    Set<UUID> libraryIds,
    Set<String> toolNames,
    AgentPlanStep planStep,
    AgentLimits limits) {

  public AgentConfiguration {
    instruction = instruction == null ? "" : instruction;
    libraryIds = libraryIds == null ? Set.of() : Set.copyOf(libraryIds);
    toolNames = toolNames == null ? Set.of() : Set.copyOf(toolNames);
    planStep = planStep == null ? AgentPlanStep.OFF : planStep;
    limits = limits == null ? AgentLimits.INSTALLATION : limits;
  }
}
