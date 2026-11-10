package io.opaa.query.web;

import io.opaa.api.dto.AgentRunLogResponse;
import io.opaa.api.dto.AgentRunLogSandbox;
import io.opaa.api.dto.AgentRunLogStep;
import io.opaa.query.runlog.AgentRunLog;
import io.opaa.query.runlog.AgentSandboxLogEntry;
import io.opaa.query.runlog.AgentStepLogEntry;

final class AgentRunLogResponseMapper {

  private AgentRunLogResponseMapper() {}

  static AgentRunLogResponse toResponse(AgentRunLog log) {
    return new AgentRunLogResponse(
        log.runId(),
        log.steps().stream().map(AgentRunLogResponseMapper::step).toList(),
        log.sandboxes().stream().map(AgentRunLogResponseMapper::sandbox).toList());
  }

  private static AgentRunLogStep step(AgentStepLogEntry entry) {
    return new AgentRunLogStep(
            entry.stepIndex(), entry.recordedAt(), entry.tool(), entry.status(), entry.durationMs())
        .hitCount(entry.hitCount())
        .abortReason(entry.abortReason());
  }

  private static AgentRunLogSandbox sandbox(AgentSandboxLogEntry entry) {
    return new AgentRunLogSandbox(
            entry.sessionRef(),
            entry.recordedAt(),
            entry.tool(),
            entry.status(),
            entry.scriptRuns(),
            entry.durationMs(),
            entry.fileCount(),
            entry.fileBytes())
        .abortReason(entry.abortReason());
  }
}
