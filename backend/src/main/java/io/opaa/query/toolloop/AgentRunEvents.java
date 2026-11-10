package io.opaa.query.toolloop;

import io.opaa.chat.AgentRunRecord;
import java.util.function.Supplier;

/**
 * What a run tells the person while it works (ADR-0070, Bestand a): the steps in the words they are
 * kept with, never a tool's raw arguments. A method may throw to end the run, e.g. when the reader
 * of a streamed answer went away; it is called outside the run's lock.
 */
interface AgentRunEvents {

  AgentRunEvents NONE = new AgentRunEvents() {};

  /**
   * Throws when the run must end now - the person stopped it, its reader went away, the instance
   * stops. Asked before every tool call and every model call.
   */
  default void checkpoint() {}

  /**
   * The checkpoint before the run is kept: after it the stop of the instance no longer ends the
   * run, so that a turn that is kept is never reported as lost.
   */
  default void lastCheckpoint() {
    checkpoint();
  }

  /** Runs the model call {@code call}; the stop of the instance may interrupt it. */
  default <T> T modelCall(Supplier<T> call) {
    return call.get();
  }

  /** The tool call at {@code index} is about to run. */
  default void stepStarted(int index, String tool, String detail) {}

  /** The step at {@code index} ended, as it is kept with the answer. */
  default void stepFinished(int index, AgentRunRecord.Step step) {}

  /** The run reached its first limit. */
  default void limitReached(AgentRunRecord.EndState limit) {}
}
