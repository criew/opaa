package io.opaa.query.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.AgentRunLogResponse;
import io.opaa.query.runlog.AgentRunLog;
import io.opaa.query.runlog.AgentSandboxLogEntry;
import io.opaa.query.runlog.AgentStepLogEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentRunLogResponseMapperTest {

  private static final Instant AT = Instant.parse("2026-11-10T08:00:00Z");

  @Test
  void mapsEveryFieldOfStepsAndSandboxes() {
    UUID runId = UUID.randomUUID();
    AgentRunLogResponse response =
        AgentRunLogResponseMapper.toResponse(
            new AgentRunLog(
                runId,
                List.of(
                    new AgentStepLogEntry(
                        2, AT, "search_knowledge", "LIMIT", 4, 120, "LIMIT_CALLS")),
                List.of(
                    new AgentSandboxLogEntry(
                        "sbx-1", AT, "run_code", "FAILED", 3, 900, 2, 2048, "COLLECT_FAILED"))));

    assertThat(response.getRunId()).isEqualTo(runId);
    var step = response.getSteps().getFirst();
    assertThat(step.getStepIndex()).isEqualTo(2);
    assertThat(step.getRecordedAt()).isEqualTo(AT);
    assertThat(step.getTool()).isEqualTo("search_knowledge");
    assertThat(step.getStatus()).isEqualTo("LIMIT");
    assertThat(step.getHitCount()).isEqualTo(4);
    assertThat(step.getDurationMs()).isEqualTo(120L);
    assertThat(step.getAbortReason()).isEqualTo("LIMIT_CALLS");
    var sandbox = response.getSandboxes().getFirst();
    assertThat(sandbox.getSessionRef()).isEqualTo("sbx-1");
    assertThat(sandbox.getRecordedAt()).isEqualTo(AT);
    assertThat(sandbox.getTool()).isEqualTo("run_code");
    assertThat(sandbox.getStatus()).isEqualTo("FAILED");
    assertThat(sandbox.getScriptRuns()).isEqualTo(3);
    assertThat(sandbox.getDurationMs()).isEqualTo(900L);
    assertThat(sandbox.getFileCount()).isEqualTo(2);
    assertThat(sandbox.getFileBytes()).isEqualTo(2048L);
    assertThat(sandbox.getAbortReason()).isEqualTo("COLLECT_FAILED");
  }
}
