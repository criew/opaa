package io.opaa.query.web;

import io.opaa.api.dto.AgentRunLogResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.query.runlog.AgentRunLogQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one read path into the step log and the sandbox log: one run, with a reason. {@code reason}
 * and {@code runId} are bound loosely and checked in the service, so a request missing or garbling
 * them reaches the service that records the refused attempt instead of failing in the binding.
 */
@RestController
public class AgentRunLogController {

  private final AgentRunLogQueryService queryService;

  public AgentRunLogController(AgentRunLogQueryService queryService) {
    this.queryService = queryService;
  }

  @GetMapping("/api/v1/admin/agent-runs/{runId}/log")
  public AgentRunLogResponse getAgentRunLog(
      @PathVariable String runId,
      @RequestParam(name = "reason", required = false) String reason,
      @Caller CurrentUser caller) {
    return AgentRunLogResponseMapper.toResponse(queryService.findRun(caller, runId, reason));
  }
}
