package io.opaa.agent;

import io.opaa.asset.AssetExtent;
import io.opaa.permission.AssetType;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** An agent's extent in the catalog: its tools. */
@Component
class AgentExtent implements AssetExtent {

  private final AgentRepository agentRepository;

  AgentExtent(AgentRepository agentRepository) {
    this.agentRepository = agentRepository;
  }

  @Override
  public AssetType assetType() {
    return Agent.ASSET_TYPE;
  }

  @Override
  public Map<UUID, Long> itemCounts(Collection<UUID> assetIds) {
    if (assetIds.isEmpty()) {
      return Map.of();
    }
    return agentRepository.countToolsByAgentIdIn(assetIds).stream()
        .collect(
            Collectors.toMap(
                AgentRepository.AgentToolCount::getAgentId,
                AgentRepository.AgentToolCount::getToolCount));
  }
}
