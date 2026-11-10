package io.opaa.query.runlog;

import java.util.List;
import java.util.UUID;

/** What the step log and the sandbox log hold about one run. */
public record AgentRunLog(
    UUID runId, List<AgentStepLogEntry> steps, List<AgentSandboxLogEntry> sandboxes) {

  public AgentRunLog {
    steps = List.copyOf(steps);
    sandboxes = List.copyOf(sandboxes);
  }
}
