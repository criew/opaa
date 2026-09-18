package io.opaa.search;

import io.opaa.indexing.chunk.ChunkContextPrefix;
import java.util.ArrayList;
import java.util.List;

/**
 * Joins stored passages back into continuous text under a character cap, and reads the heading path
 * out of a Fundort.
 *
 * <p>The join is overlap-aware because chunking is: two adjoining chunks deliberately share their
 * boundary sentences ({@code opaa.indexing.chunk-overlap}), and concatenating them verbatim would
 * hand a foreign model the same sentences twice - which reads like a repetition in the original.
 * The shared part is therefore written once.
 *
 * <p>The cap is enforced <b>while</b> joining, not afterwards: {@link Joiner#isFull()} lets the
 * caller stop reading further passages, so the cap bounds the work and the memory, not only the
 * length of the answer.
 */
final class PassageText {

  /** Separates two passages that share nothing - a chunk boundary is a paragraph boundary. */
  private static final String SEPARATOR = "\n\n";

  /** The marker {@code ChunkLocationResolver} puts in front of a heading path. */
  private static final String SECTION_LOCATION_MARKER = "Abschn. ";

  private PassageText() {}

  /** {@code passages} joined without a cap - the whole selection is known to be small. */
  static Joined join(List<String> passages, int limit) {
    Joiner joiner = new Joiner(limit);
    for (String passage : passages) {
      joiner.append(passage);
      if (joiner.isFull()) {
        break;
      }
    }
    return joiner.finish();
  }

  /**
   * Accumulates passages up to a character cap. Once {@link #isFull()} answers {@code true}, no
   * further passage changes the result, which is what lets a caller stop loading them.
   */
  static final class Joiner {

    private final StringBuilder text = new StringBuilder();
    private final int limit;
    private boolean truncated;

    Joiner(int limit) {
      this.limit = limit;
    }

    boolean isFull() {
      return text.length() >= limit;
    }

    void append(String passage) {
      if (passage == null || passage.isEmpty() || isFull()) {
        return;
      }
      if (text.isEmpty()) {
        text.append(passage);
      } else {
        int overlap = overlapLength(text, passage);
        if (overlap > 0) {
          text.append(passage, overlap, passage.length());
        } else {
          text.append(SEPARATOR).append(passage);
        }
      }
      if (text.length() > limit) {
        truncated = true;
      }
    }

    /**
     * The accumulated text, cut to the cap. The cut never splits a surrogate pair: a lone high
     * surrogate at the end would be an invalid character in the JSON response.
     */
    Joined finish() {
      if (text.length() <= limit) {
        return new Joined(text.toString(), truncated);
      }
      int end = limit;
      if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
        end--;
      }
      return new Joined(text.substring(0, end), true);
    }
  }

  /** The joined text and whether the cap cut anything off. */
  record Joined(String text, boolean truncated) {}

  /**
   * The length of the longest suffix of {@code written} that is a prefix of {@code next}, or 0.
   * Bounded by {@code next}'s length, so the cost stays linear in the chunk size rather than in the
   * document size.
   */
  private static int overlapLength(CharSequence written, String next) {
    int max = Math.min(written.length(), next.length());
    for (int length = max; length > 0; length--) {
      int start = written.length() - length;
      if (regionMatches(written, start, next, length)) {
        return length;
      }
    }
    return 0;
  }

  private static boolean regionMatches(
      CharSequence written, int writtenStart, String next, int length) {
    for (int i = 0; i < length; i++) {
      if (written.charAt(writtenStart + i) != next.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  /**
   * The headings above a passage, outermost first, read from its Fundort ("Abschn. A › B › C").
   * Empty for a Fundort that names a page, a slide or a row - those name a position, not a
   * structure - and for a passage without one.
   */
  static List<String> headingPath(String location) {
    if (location == null || !location.startsWith(SECTION_LOCATION_MARKER)) {
      return List.of();
    }
    String path = location.substring(SECTION_LOCATION_MARKER.length()).strip();
    if (path.isEmpty()) {
      return List.of();
    }
    List<String> headings = new ArrayList<>();
    for (String heading : path.split(ChunkContextPrefix.SEPARATOR.strip(), -1)) {
      String stripped = heading.strip();
      if (!stripped.isEmpty()) {
        headings.add(stripped);
      }
    }
    return List.copyOf(headings);
  }
}
