package io.opaa.query;

import java.util.List;

/**
 * Renders the Gesprächsnotiz block of a system prompt (#1487, docs/features/conversation-memory.md,
 * "Wie die Notiz ins Modell kommt"): the same header and the same bullet form for both calls,
 * differently filled - the sub-question decomposition gets the {@code RAHMEN} points, the answer
 * all of them.
 *
 * <p>The header names the block as Angaben of the asking person and explicitly denies it the status
 * of a source, so a model cannot mistake a note point for retrieved context worth citing.
 *
 * <p>Without a single point there is no block at all, not an empty one - an empty heading would
 * still be an instruction to consider something.
 */
public final class ConversationNoteBlock {

  private ConversationNoteBlock() {}

  static final String HEADER =
      "Gesprächsnotiz — Angaben der fragenden Person aus diesem Gespräch (kein Beleg, keine"
          + " Quelle):";

  /** The rendered block, or {@code null} when {@code points} is empty. */
  public static String render(List<String> points) {
    if (points.isEmpty()) {
      return null;
    }
    StringBuilder block = new StringBuilder(HEADER);
    points.forEach(point -> block.append("\n- ").append(point));
    return block.toString();
  }
}
