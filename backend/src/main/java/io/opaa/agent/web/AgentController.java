package io.opaa.agent.web;

import io.opaa.agent.AgentChoice;
import io.opaa.agent.AgentService;
import io.opaa.agent.AgentSwitch;
import io.opaa.api.dto.AgentAvailabilityResponse;
import io.opaa.api.dto.AgentCreateRequest;
import io.opaa.api.dto.AgentResponse;
import io.opaa.api.dto.AgentUpdateRequest;
import io.opaa.api.dto.AssetLockRequest;
import io.opaa.api.dto.AssetResponsibilityResponse;
import io.opaa.api.dto.AvailableAgent;
import io.opaa.asset.web.AssetResponsibilityResponseMapper;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agents. Grants, the Herleitung and the space association of an agent are the asset shell's, under
 * {@code /api/v1/assets/AGENT/{assetId}}; while agents are switched off, every path here but the
 * availability answers like an unknown address.
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {

  private final AgentService agentService;
  private final AgentSwitch agentSwitch;
  private final AgentChoice agentChoice;

  public AgentController(
      AgentService agentService, AgentSwitch agentSwitch, AgentChoice agentChoice) {
    this.agentService = agentService;
    this.agentSwitch = agentSwitch;
    this.agentChoice = agentChoice;
  }

  @GetMapping
  public List<AgentResponse> listAgents(@Caller CurrentUser caller) {
    return AgentResponseMapper.toResponses(agentService.list(caller));
  }

  @PostMapping
  public ResponseEntity<AgentResponse> createAgent(
      @Valid @RequestBody AgentCreateRequest request, @Caller CurrentUser caller) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            AgentResponseMapper.toResponse(
                agentService.create(AgentResponseMapper.toCreation(request), caller)));
  }

  @GetMapping("/available")
  public List<AvailableAgent> listAvailableAgents(
      @RequestParam UUID spaceId, @Caller CurrentUser caller) {
    return agentChoice.available(caller, spaceId).stream()
        .map(
            agent ->
                new AvailableAgent(agent.getId(), agent.getName())
                    .description(agent.getDescription()))
        .toList();
  }

  @GetMapping("/availability")
  public AgentAvailabilityResponse getAgentAvailability(@Caller CurrentUser caller) {
    return new AgentAvailabilityResponse(agentSwitch.isEnabled());
  }

  @GetMapping("/{agentId}")
  public AgentResponse getAgent(@PathVariable UUID agentId, @Caller CurrentUser caller) {
    return AgentResponseMapper.toResponse(agentService.get(agentId, caller));
  }

  @PutMapping("/{agentId}")
  public AgentResponse updateAgent(
      @PathVariable UUID agentId,
      @Valid @RequestBody AgentUpdateRequest request,
      @Caller CurrentUser caller) {
    return AgentResponseMapper.toResponse(
        agentService.update(agentId, AgentResponseMapper.toUpdate(request), caller));
  }

  @PutMapping("/{agentId}/lock")
  public AssetResponsibilityResponse lockAgent(
      @PathVariable UUID agentId,
      @Valid @RequestBody AssetLockRequest request,
      @Caller CurrentUser caller) {
    return AssetResponsibilityResponseMapper.toResponse(
        agentService.lock(agentId, request.getReason(), caller));
  }

  @DeleteMapping("/{agentId}/lock")
  public AssetResponsibilityResponse unlockAgent(
      @PathVariable UUID agentId, @Caller CurrentUser caller) {
    return AssetResponsibilityResponseMapper.toResponse(agentService.unlock(agentId, caller));
  }

  @DeleteMapping("/{agentId}")
  public ResponseEntity<Void> deleteAgent(@PathVariable UUID agentId, @Caller CurrentUser caller) {
    agentService.delete(agentId, caller);
    return ResponseEntity.noContent().build();
  }
}
