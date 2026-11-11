package io.opaa.query.toolloop;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the agent a person chose in the chat. Implemented by the agent catalogue; without an
 * implementation every agent request is refused.
 */
public interface AgentRunSpecResolver {

  /**
   * The agent's run spec, or empty when the agent is unknown, belongs to another organization, is
   * not visible to the caller, not assigned to {@code spaceId}, or agents are switched off - all
   * alike, so a foreign id is never confirmed. An agent the caller may choose but the system
   * administration locked throws a {@code ConflictException} with code {@code AGENT_LOCKED}.
   */
  Optional<AgentRunSpec> resolve(
      UUID agentId, UUID organizationId, UUID userId, UUID spaceId, Set<UUID> readableLibraryIds);
}
