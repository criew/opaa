package io.opaa.agent;

import io.opaa.chat.AgentRevisions;
import io.opaa.permission.AssetAccessService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The current revision of stamped agents for a chat: only agents of the organization the person may
 * read by the formula ({@link AssetAccessService#readableAssetIds}), and none while agents are
 * switched off, so the comparison at an answer tells no more than the agent's own page.
 */
@Component
class AgentCurrentRevisions implements AgentRevisions {

  private final AgentRepository agentRepository;
  private final AgentSwitch agentSwitch;
  private final AssetAccessService accessService;

  AgentCurrentRevisions(
      AgentRepository agentRepository, AgentSwitch agentSwitch, AssetAccessService accessService) {
    this.agentRepository = agentRepository;
    this.agentSwitch = agentSwitch;
    this.accessService = accessService;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, Integer> current(Set<UUID> agentIds, UUID organizationId, UUID userId) {
    if (agentIds.isEmpty() || !agentSwitch.isEnabled()) {
      return Map.of();
    }
    Set<UUID> readable =
        new HashSet<>(accessService.readableAssetIds(Agent.ASSET_TYPE, userId, organizationId));
    readable.retainAll(agentIds);
    Map<UUID, Integer> revisions = new HashMap<>();
    for (Agent agent : agentRepository.findAllById(readable)) {
      if (agent.getOrganizationId().equals(organizationId)) {
        revisions.put(agent.getId(), agent.getRevision());
      }
    }
    return revisions;
  }
}
