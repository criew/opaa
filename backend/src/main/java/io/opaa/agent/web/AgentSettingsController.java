package io.opaa.agent.web;

import io.opaa.agent.AgentSettingsService;
import io.opaa.api.dto.AgentSettingsResponse;
import io.opaa.api.dto.AgentSettingsUpdateRequest;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import jakarta.validation.Valid;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The installation-wide settings of agents (ADR-0070), {@code SYSTEM_ADMIN} only. */
@RestController
@RequestMapping("/api/v1/system/agents")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AgentSettingsController {

  private final AgentSettingsService settingsService;

  public AgentSettingsController(AgentSettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @GetMapping
  public AgentSettingsResponse getAgentSettings(@Caller CurrentUser caller) {
    return AgentResponseMapper.toResponse(settingsService.current(caller.organizationId()));
  }

  @PutMapping
  public AgentSettingsResponse updateAgentSettings(
      @Valid @RequestBody AgentSettingsUpdateRequest request, @Caller CurrentUser caller) {
    return AgentResponseMapper.toResponse(
        settingsService.update(
            caller,
            Boolean.TRUE.equals(request.getEnabled()),
            request.getEnabledTools() == null ? Set.of() : Set.copyOf(request.getEnabledTools())));
  }
}
