package io.opaa.agent.web;

import io.opaa.agent.Agent;
import io.opaa.agent.AgentConfiguration;
import io.opaa.agent.AgentCreation;
import io.opaa.agent.AgentLimits;
import io.opaa.agent.AgentSettingsService;
import io.opaa.agent.AgentTools;
import io.opaa.agent.AgentUpdate;
import io.opaa.agent.AgentView;
import io.opaa.api.dto.AgentCreateRequest;
import io.opaa.api.dto.AgentLibrary;
import io.opaa.api.dto.AgentResponse;
import io.opaa.api.dto.AgentSettingsResponse;
import io.opaa.api.dto.AgentTemplate;
import io.opaa.api.dto.AgentUpdateRequest;
import io.opaa.api.dto.AssetReachResponse;
import io.opaa.asset.web.AssetResponsibilityResponseMapper;
import io.opaa.permission.AssetReach;
import io.opaa.permission.web.SuccessionStateResponseMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Maps agents and their settings between the API and the domain of {@code io.opaa.agent}. */
final class AgentResponseMapper {

  private AgentResponseMapper() {}

  static AgentCreation toCreation(AgentCreateRequest request) {
    return new AgentCreation(
        request.getName(),
        request.getDescription(),
        request.getOwnerType(),
        request.getOwnerId(),
        AssetResponsibilityResponseMapper.toUnit(request.getResponsible()));
  }

  static AgentUpdate toUpdate(AgentUpdateRequest request) {
    io.opaa.api.dto.AgentLimits limits = request.getLimits();
    return new AgentUpdate(
        request.getName(),
        request.getDescription(),
        new AgentConfiguration(
            request.getInstruction(),
            request.getLibraryIds() == null ? Set.of() : Set.copyOf(request.getLibraryIds()),
            request.getToolNames() == null ? Set.of() : Set.copyOf(request.getToolNames()),
            request.getPlanStep(),
            limits == null
                ? AgentLimits.INSTALLATION
                : new AgentLimits(
                    limits.getMaxToolCalls(),
                    limits.getMaxSearchCalls(),
                    limits.getMaxDurationSeconds(),
                    limits.getMaxCollectedChars()),
            request.getTemplateIds() == null ? null : Set.copyOf(request.getTemplateIds())),
        AssetResponsibilityResponseMapper.toUnit(request.getResponsible()));
  }

  static AgentResponse toResponse(AgentView view) {
    Agent agent = view.agent();
    AgentConfiguration configuration = agent.configuration();
    AgentLimits limits = configuration.limits();
    return new AgentResponse(
            agent.getId(),
            agent.getName(),
            view.instructionReadable() ? configuration.instruction() : null,
            view.libraries().stream()
                .map(library -> new AgentLibrary(library.id(), library.name()))
                .toList(),
            view.hiddenLibraryCount(),
            view.templates().stream()
                .map(template -> new AgentTemplate(template.id(), template.name()))
                .toList(),
            view.hiddenTemplateCount(),
            ordered(configuration.toolNames()),
            configuration.planStep(),
            new io.opaa.api.dto.AgentLimits()
                .maxToolCalls(limits.maxToolCalls())
                .maxSearchCalls(limits.maxSearchCalls())
                .maxDurationSeconds(limits.maxDurationSeconds())
                .maxCollectedChars(limits.maxCollectedChars()),
            agent.getRevision(),
            agent.isDelivered(),
            agent.getOwnerType(),
            agent.getOwnerId(),
            toReachResponse(view.reach()),
            view.myRole(),
            AssetResponsibilityResponseMapper.toResponse(view.responsibility()),
            agent.getCreatedAt(),
            agent.getUpdatedAt())
        .description(agent.getDescription())
        .ownerName(view.ownerName())
        .succession(SuccessionStateResponseMapper.toStateResponse(view.succession()));
  }

  static List<AgentResponse> toResponses(List<AgentView> views) {
    return views.stream().map(AgentResponseMapper::toResponse).toList();
  }

  static AgentSettingsResponse toResponse(AgentSettingsService.View view) {
    return new AgentSettingsResponse(
            view.enabled(), ordered(view.enabledTools()), AgentTools.ASSIGNABLE, view.updatedAt())
        .updatedBy(view.updatedBy());
  }

  /** Tool names in the display order of {@link AgentTools#ASSIGNABLE}. */
  private static List<String> ordered(Set<String> toolNames) {
    List<String> ordered = new ArrayList<>();
    for (String tool : AgentTools.ASSIGNABLE) {
      if (toolNames.contains(tool)) {
        ordered.add(tool);
      }
    }
    return ordered;
  }

  /** The derived reach - three figures, no stored level. */
  private static AssetReachResponse toReachResponse(AssetReach reach) {
    return new AssetReachResponse(reach.allAccounts(), reach.groupCount(), reach.userCount());
  }
}
