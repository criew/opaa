package io.opaa.agent;

import io.opaa.api.types.AgentPlanStep;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.permission.AssetAccessService;
import io.opaa.permission.SpaceAssetDirectory;
import io.opaa.query.toolloop.AgentLimits;
import io.opaa.query.toolloop.AgentRunSpec;
import io.opaa.query.toolloop.AgentRunSpecResolver;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The agents a person can choose in a chat: associated with the chat's space and readable by the
 * formula, while agents are switched on. The offer in the @ menu ({@link #available}) and the check
 * of a chosen agent ({@link #resolve}) apply the same rule, so an agent the menu does not offer is
 * refused when sent. Choosing grants nothing: a run reads only what the caller may read.
 */
@Service
@Transactional(readOnly = true)
public class AgentChoice implements AgentRunSpecResolver {

  private final AgentRepository agentRepository;
  private final AgentSwitch agentSwitch;
  private final AssetAccessService accessService;
  private final SpaceAssetDirectory spaceAssets;

  AgentChoice(
      AgentRepository agentRepository,
      AgentSwitch agentSwitch,
      AssetAccessService accessService,
      SpaceAssetDirectory spaceAssets) {
    this.agentRepository = agentRepository;
    this.agentSwitch = agentSwitch;
    this.accessService = accessService;
    this.spaceAssets = spaceAssets;
  }

  /**
   * The agents the caller may choose in a chat of {@code spaceId}, by name; empty while agents are
   * switched off. An unknown space is a {@code 404}, a caller who is no member a {@code 403}, as
   * for the prompts of the space.
   */
  public List<Agent> available(CurrentUser caller, UUID spaceId) {
    Set<UUID> offered =
        new LinkedHashSet<>(spaceAssets.assetIdsInSpace(spaceId, Agent.ASSET_TYPE, caller));
    if (!agentSwitch.isEnabled()) {
      return List.of();
    }
    offered.retainAll(
        accessService.readableAssetIds(Agent.ASSET_TYPE, caller.id(), caller.organizationId()));
    return agentRepository.findAllById(offered).stream()
        .filter(agent -> agent.getOrganizationId().equals(caller.organizationId()))
        .sorted(Comparator.comparing(Agent::getName).thenComparing(Agent::getId))
        .toList();
  }

  @Override
  public Optional<AgentRunSpec> resolve(
      UUID agentId, UUID organizationId, UUID userId, UUID spaceId, Set<UUID> readableLibraryIds) {
    if (agentId == null || spaceId == null || !agentSwitch.isEnabled()) {
      return Optional.empty();
    }
    CurrentUser caller = CurrentUser.of(userId, organizationId, SystemRole.USER, null);
    try {
      if (!spaceAssets.assetIdsInSpace(spaceId, Agent.ASSET_TYPE, caller).contains(agentId)
          || !accessService
              .readableAssetIds(Agent.ASSET_TYPE, userId, organizationId)
              .contains(agentId)) {
        return Optional.empty();
      }
    } catch (NotFoundException | AccessDeniedException notInSpace) {
      return Optional.empty();
    }
    return agentRepository
        .findById(agentId)
        .filter(agent -> agent.getOrganizationId().equals(organizationId))
        .map(AgentChoice::spec);
  }

  private static AgentRunSpec spec(Agent agent) {
    AgentConfiguration configuration = agent.configuration();
    io.opaa.agent.AgentLimits limits = configuration.limits();
    return new AgentRunSpec(
        agent.getId(),
        agent.getRevision(),
        configuration.instruction(),
        configuration.libraryIds(),
        configuration.toolNames(),
        new AgentLimits(
            limits.maxToolCalls(),
            limits.maxSearchCalls(),
            limits.maxDurationSeconds() == null
                ? null
                : Duration.ofSeconds(limits.maxDurationSeconds()),
            limits.maxCollectedChars()),
        configuration.planStep() == AgentPlanStep.CONFIRM,
        agent.isDelivered(),
        agent.getName());
  }
}
