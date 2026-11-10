package io.opaa.query.answer;

import io.opaa.chat.AgentRunRecord;
import java.io.IOException;
import java.util.UUID;

/**
 * Receives a streamed answer on the thread that asked for it. A method that throws ends the stream:
 * the model call is cancelled and nothing of the turn is kept.
 */
public interface AnswerStreamListener {

  /**
   * The model has begun to answer. Until here a failure is the failure of the whole request; from
   * here on it can only be told within the stream.
   */
  void started() throws IOException;

  /** Text appended to the provisional answer, or - {@code replace} - replacing all of it. */
  void provisional(String text, boolean replace) throws IOException;

  /** Nothing was written for a while; a chance to notice that the reader went away. */
  void idle() throws IOException;

  /**
   * The answer is complete and the turn is about to be kept - the last chance to notice that the
   * reader went away, so that a turn it never receives is not kept either.
   */
  default void beforeKeeping() throws IOException {
    idle();
  }

  /**
   * An agent run holds its place (ADR-0070): {@code runId} names it, {@code cancelToken} is the
   * secret that ends it from any instance. Never sent on an ordinary turn.
   */
  default void runStarted(UUID runId, String cancelToken) throws IOException {}

  /**
   * An agent run's tool call at {@code index} begins (ADR-0070): {@code tool} is the tool's name,
   * {@code detail} what the call is about in the person's words, {@code null} when not yet known.
   * Never sent on an ordinary turn.
   */
  default void stepStarted(int index, String tool, String detail) throws IOException {}

  /** The step at {@code index} ended as it is kept with the answer. */
  default void stepFinished(int index, AgentRunRecord.Step step) throws IOException {}

  /** The run reached {@code limit} and answers with what it has; sent once per run. */
  default void limitReached(AgentRunRecord.EndState limit) throws IOException {}

  /** The run ended in {@code endState}; its provisional answer and the kept turn follow. */
  default void runFinished(AgentRunRecord.EndState endState) throws IOException {}
}
