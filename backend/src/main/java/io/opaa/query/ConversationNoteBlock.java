package io.opaa.query;

import java.util.List;

/**
 * The Gesprächsnotiz block of a system prompt (#1487, docs/features/conversation-memory.md, "Wie
 * die Notiz ins Modell kommt"): the same header and the same bullet form for both calls,
 * differently filled - the sub-question decomposition gets the {@code RAHMEN} points, the answer
 * all of them.
 *
 * <p>The header names the block as Angaben of the asking person and explicitly denies it the status
 * of a source, so a model cannot mistake a note point for retrieved context worth citing.
 *
 * <p><b>Two values from one rendering, never assembled separately.</b> {@link #modelText()} is what
 * the model sees, {@link #anchorTexts()} what the safety belt of {@code QueryDecompositionService}
 * may anchor against - the points alone, without this class's own German boilerplate. The header is
 * prompt wording, not material of the asking person: {@code QueryDecompositionService#isRelated}
 * matches on substring containment from four characters, so a header token like "person" would make
 * a degenerate sub-query ("Personalausweis beantragen") count as related to a conversation about
 * something else entirely, and the belt would not fire where it exists to fire. Both values come
 * out of {@link #render(List)} together, so they cannot drift apart the way a separately maintained
 * enumeration would.
 *
 * <p>Without a single point there is no block at all, not an empty one - an empty heading would
 * still be an instruction to consider something.
 */
public record ConversationNoteBlock(String modelText, List<String> anchorTexts) {

  public ConversationNoteBlock {
    anchorTexts = List.copyOf(anchorTexts);
  }

  static final String HEADER =
      "Gesprächsnotiz — Angaben der fragenden Person aus diesem Gespräch (kein Beleg, keine"
          + " Quelle):";

  /** The rendered block, or {@code null} when {@code points} is empty. */
  public static ConversationNoteBlock render(List<String> points) {
    if (points.isEmpty()) {
      return null;
    }
    StringBuilder modelText = new StringBuilder(HEADER);
    points.forEach(point -> modelText.append("\n- ").append(point));
    return new ConversationNoteBlock(modelText.toString(), points);
  }
}
