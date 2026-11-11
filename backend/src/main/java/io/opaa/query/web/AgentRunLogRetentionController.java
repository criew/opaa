package io.opaa.query.web;

import io.opaa.api.dto.AgentRunLogRetention;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.query.runlog.AgentRunLogRetentionService;
import io.opaa.query.runlog.AgentRunLogRetentionSettings;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The retention periods of the step log and the sandbox log; the service admits admins only. */
@RestController
@RequestMapping("/api/v1/admin/retention/agent-run-logs")
public class AgentRunLogRetentionController {

  private final AgentRunLogRetentionService service;

  public AgentRunLogRetentionController(AgentRunLogRetentionService service) {
    this.service = service;
  }

  @GetMapping
  public AgentRunLogRetention getAgentRunLogRetention(@Caller CurrentUser caller) {
    return toResponse(service.read(caller));
  }

  @PutMapping
  public AgentRunLogRetention updateAgentRunLogRetention(
      @Valid @RequestBody AgentRunLogRetention request, @Caller CurrentUser caller) {
    return toResponse(
        service.updateRetentionDays(
            caller, request.getStepLogRetentionDays(), request.getSandboxLogRetentionDays()));
  }

  private static AgentRunLogRetention toResponse(AgentRunLogRetentionSettings settings) {
    return new AgentRunLogRetention(
        settings.getStepLogRetentionDays(), settings.getSandboxLogRetentionDays());
  }
}
