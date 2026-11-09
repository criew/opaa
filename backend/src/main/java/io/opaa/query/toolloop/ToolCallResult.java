package io.opaa.query.toolloop;

import io.opaa.chat.AgentRunRecord.StepStatus;
import java.util.Locale;

/**
 * The outcome class of one tool call: the value of the metric tag {@code result} (ADR-0071,
 * Entscheidung 2) and the status of the step the person sees.
 */
public enum ToolCallResult {
  SUCCESS(StepStatus.DONE),
  EMPTY(StepStatus.EMPTY),
  /** Ran, but an argument outside the caller's scope was ignored (ADR-0070, Entscheidung 3). */
  ARGUMENT_IGNORED(StepStatus.ARGUMENT_IGNORED),
  INVALID_ARGUMENTS(StepStatus.INVALID_ARGUMENTS),
  UNKNOWN_TOOL(StepStatus.UNKNOWN_TOOL),
  DENIED(StepStatus.DENIED),
  TIMEOUT(StepStatus.ERROR),
  ERROR(StepStatus.ERROR);

  private final StepStatus stepStatus;

  ToolCallResult(StepStatus stepStatus) {
    this.stepStatus = stepStatus;
  }

  StepStatus stepStatus() {
    return stepStatus;
  }

  String tagValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
