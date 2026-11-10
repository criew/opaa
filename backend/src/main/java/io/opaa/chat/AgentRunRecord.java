package io.opaa.chat;

import java.util.List;
import java.util.UUID;

/**
 * What a person sees of an agent run at its answer, kept with the answer's message (ADR-0070,
 * Entscheidung 4, Bestand a): the run's id, how it ended, and each step with its sub-question and
 * hit count. It lives and is deleted with the chat.
 *
 * @param endState how the run ended; anything but {@link EndState#COMPLETED} is shown as a set-off
 *     state "nicht geprüft", never as an error.
 */
public record AgentRunRecord(UUID runId, EndState endState, List<Step> steps) {

  public AgentRunRecord {
    steps = steps == null ? List.of() : List.copyOf(steps);
  }

  /** How a run ended. */
  public enum EndState {
    COMPLETED,
    LIMIT_CALLS,
    LIMIT_DURATION,
    LIMIT_VOLUME
  }

  /** The outcome of one step as the person reads it. */
  public enum StepStatus {
    DONE,
    EMPTY,
    ARGUMENT_IGNORED,
    INVALID_ARGUMENTS,
    UNKNOWN_TOOL,
    DENIED,
    LIMIT,
    ERROR
  }

  /**
   * One tool call.
   *
   * @param tool the tool's name, or {@code unknown} for one the model invented.
   * @param detail what the step was about in the person's words, e.g. the sub-question of a search;
   *     {@code null} when the tool has none.
   * @param hits the number of passages found, {@code null} for a tool that finds none.
   */
  public record Step(String tool, String detail, Integer hits, StepStatus status) {}
}
