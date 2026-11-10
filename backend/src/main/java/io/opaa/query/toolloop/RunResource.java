package io.opaa.query.toolloop;

import io.opaa.chat.ChatAttachment;
import java.util.List;

/**
 * Something a tool opened for the length of one run, such as a sandbox (ADR-0073). The loop calls
 * {@link #finish()} once when the run has its answer and before the answer is kept, and {@link
 * #close()} when the run ends in any outcome; a resource never outlives its run.
 */
interface RunResource {

  /**
   * The files the resource produced for the asker, already taken as unbound attachments; the loop
   * binds them to the answer's message. A failure fails the turn.
   */
  default List<ChatAttachment> finish() {
    return List.of();
  }

  /** Releases the resource; never throws. */
  void close();
}
