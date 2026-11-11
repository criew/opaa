package io.opaa.chat;

import java.util.List;
import java.util.UUID;

/**
 * What a person sees of an agent run at its answer, kept with the answer's message (ADR-0070,
 * Entscheidung 4, Bestand a): the run's id, how it ended, and each step with its sub-question and
 * hit count, and the version stamp. It lives and is deleted with the chat.
 *
 * @param endState how the run ended; a limit is shown as a set-off state "nicht geprüft", never as
 *     an error; {@link EndState#ASK_USER} as a question back to the person, {@link EndState#PLAN}
 *     as a plan to confirm or change.
 * @param agentId the agent that ran; {@code null} in records kept before it was recorded.
 * @param agentName the agent's name at the time of the run, shown at the answer.
 * @param stamp the rest of the version stamp - revision, libraries, templates, model; {@code null}
 *     in records kept before it was recorded. Agent id and name are this record's, never repeated.
 */
public record AgentRunRecord(
    UUID runId,
    EndState endState,
    List<Step> steps,
    UUID agentId,
    String agentName,
    AgentRunStamp stamp) {

  /** A record without the agent named. */
  public AgentRunRecord(UUID runId, EndState endState, List<Step> steps) {
    this(runId, endState, steps, null, null, null);
  }

  /** A record without a stamp. */
  public AgentRunRecord(
      UUID runId, EndState endState, List<Step> steps, UUID agentId, String agentName) {
    this(runId, endState, steps, agentId, agentName, null);
  }

  public AgentRunRecord {
    steps = steps == null ? List.of() : List.copyOf(steps);
  }

  /** How a run ended. */
  public enum EndState {
    COMPLETED,
    LIMIT_CALLS,
    LIMIT_DURATION,
    LIMIT_VOLUME,
    /** The agent asked the person back; the answer is the question, the run ended there. */
    ASK_USER,
    /**
     * The agent asks for confirmation of its plan; the answer is the plan, the run ended there and
     * the next question with the same agent works it off in a new run.
     */
    PLAN
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
