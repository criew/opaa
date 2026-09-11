package io.opaa.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the Gesprächsnotiz does with new candidates, independently of where the note is stored
 * (#1487): the persistent path applies it to database rows, the multi-turn evaluation harness to an
 * in-memory list, and both must behave identically or the harness measures a note production never
 * builds.
 *
 * <p>Two rules, both from docs/features/conversation-memory.md, "Lebenszyklus": a candidate equal
 * to a point the note <em>currently</em> holds - ignoring case and punctuation - is not appended
 * again, and the note never grows past its cap, the oldest point falling out first. Deliberately
 * checked against the current list only, never against removed points: a removed point is not
 * blocked for the future (the decided Löschsemantik), and the person removes it again.
 */
public final class ChatNoteList {

  private ChatNoteList() {}

  /**
   * The candidates that actually enter the note, in order: those whose text no current point and no
   * earlier candidate of the same batch already carries.
   *
   * @param currentTexts the texts of the note's current points, in any order
   */
  public static List<ChatNoteCandidate> accept(
      List<String> currentTexts, List<ChatNoteCandidate> candidates) {
    Set<String> seen = new LinkedHashSet<>();
    currentTexts.forEach(text -> seen.add(normalize(text)));
    List<ChatNoteCandidate> accepted = new ArrayList<>(candidates.size());
    for (ChatNoteCandidate candidate : candidates) {
      if (seen.add(normalize(candidate.text()))) {
        accepted.add(candidate);
      }
    }
    return List.copyOf(accepted);
  }

  /**
   * How many of the oldest points have to go so that {@code currentSize + appended} fits into
   * {@code cap} - never more than {@code currentSize}, so a batch larger than the whole cap drops
   * the note but not more.
   */
  public static int overflow(int currentSize, int appended, int cap) {
    return Math.min(currentSize, Math.max(0, currentSize + appended - cap));
  }

  /** Lower-cased, stripped of punctuation, whitespace collapsed - the equality of two points. */
  static String normalize(String text) {
    return text.toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}\\s]", "")
        .replaceAll("\\s+", " ")
        .strip();
  }
}
