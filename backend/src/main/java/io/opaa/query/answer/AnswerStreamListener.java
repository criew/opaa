package io.opaa.query.answer;

import java.io.IOException;

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
}
