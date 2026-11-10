package io.opaa.query.toolloop;

import java.util.Set;
import java.util.UUID;

/**
 * What an agent brings to a run (ADR-0070): its instruction, libraries, tools and limits. Built by
 * the {@link AgentRunSpecResolver} for an agent the caller may use; nothing in it widens the
 * caller's rights - the libraries are intersected with what the caller may read, the tools with
 * those registered and switched on.
 *
 * @param version the agent's version the run used, for the answer's stamp.
 * @param instruction the agent's instruction, appended to the fixed rules, never replacing them;
 *     {@code null} or blank for none.
 * @param builtin {@code true} only for an agent the installation ships; the metric tag {@code
 *     kind}.
 * @param confirmPlan whether the agent asks for confirmation of a plan before it works.
 * @param name the agent's name as the person chose it, kept with the answer; {@code null} for none.
 */
public record AgentRunSpec(
    UUID agentId,
    int version,
    String instruction,
    Set<UUID> libraryIds,
    Set<String> toolNames,
    AgentLimits limits,
    boolean confirmPlan,
    boolean builtin,
    String name) {

  /** A spec without a name to show at the answer. */
  public AgentRunSpec(
      UUID agentId,
      int version,
      String instruction,
      Set<UUID> libraryIds,
      Set<String> toolNames,
      AgentLimits limits,
      boolean confirmPlan,
      boolean builtin) {
    this(agentId, version, instruction, libraryIds, toolNames, limits, confirmPlan, builtin, null);
  }

  public AgentRunSpec {
    libraryIds = libraryIds == null ? Set.of() : Set.copyOf(libraryIds);
    toolNames = toolNames == null ? Set.of() : Set.copyOf(toolNames);
    limits = limits == null ? AgentLimits.SYSTEM : limits;
  }
}
