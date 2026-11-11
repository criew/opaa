package io.opaa.chat;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The current revision of the agents named in a chat's stamps, so an answer can say "mit Fassung N
 * erstellt, aktuell Fassung M". Implemented by the agent catalogue; it answers only agents of
 * {@code organizationId} the person may read by the formula while agents are switched on and leaves
 * every other id out, so a revision never tells more than the agent's own page would.
 */
public interface AgentRevisions {

  /** The current revision by agent id; ids the person may not read are absent. */
  Map<UUID, Integer> current(Set<UUID> agentIds, UUID organizationId, UUID userId);
}
