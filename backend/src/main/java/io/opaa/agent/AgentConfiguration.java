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
 * @param templateIds the document templates the agent fills its result into; empty offers no
 *     template. {@code null} only in a change and keeps the stored assignment.
 */
public record AgentConfiguration(
    String instruction,
    Set<UUID> libraryIds,
    Set<String> toolNames,
    AgentPlanStep planStep,
    AgentLimits limits,
    Set<UUID> templateIds) {

  public AgentConfiguration {
    instruction = instruction == null ? "" : instruction;
    libraryIds = libraryIds == null ? Set.of() : Set.copyOf(libraryIds);
    toolNames = toolNames == null ? Set.of() : Set.copyOf(toolNames);
    planStep = planStep == null ? AgentPlanStep.OFF : planStep;
    limits = limits == null ? AgentLimits.INSTALLATION : limits;
    templateIds = templateIds == null ? null : Set.copyOf(templateIds);
  }

  /** A configuration that leaves the template assignment as stored. */
  public AgentConfiguration(
      String instruction,
      Set<UUID> libraryIds,
      Set<String> toolNames,
      AgentPlanStep planStep,
      AgentLimits limits) {
    this(instruction, libraryIds, toolNames, planStep, limits, null);
  }
}
