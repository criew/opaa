package io.opaa.query.toolloop;

import io.opaa.chat.AgentRunRecord;

/**
 * What a run tells the person while it works (ADR-0070, Bestand a): the steps in the words they are
 * kept with, never a tool's raw arguments. A method may throw to end the run, e.g. when the reader
 * of a streamed answer went away; it is called outside the run's lock.
 */
interface AgentRunEvents {

  AgentRunEvents NONE = new AgentRunEvents() {};

  /** The tool call at {@code index} is about to run. */
  default void stepStarted(int index, String tool, String detail) {}

  /** The step at {@code index} ended, as it is kept with the answer. */
  default void stepFinished(int index, AgentRunRecord.Step step) {}

  /** The run reached its first limit. */
  default void limitReached(AgentRunRecord.EndState limit) {}
}
