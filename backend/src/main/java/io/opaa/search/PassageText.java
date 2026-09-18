package io.opaa.search;

import io.opaa.indexing.chunk.ChunkContextPrefix;
import java.util.ArrayList;
import java.util.List;

/**
 * Joins stored passages back into continuous text and reads the heading path out of a Fundort.
 *
 * <p>The join is overlap-aware because chunking is: two adjoining chunks deliberately share their
 * boundary sentences ({@code opaa.indexing.chunk-overlap}), and concatenating them verbatim would
 * hand a foreign model the same sentences twice - which reads like a repetition in the original.
 * The shared part is therefore written once.
 */
final class PassageText {

  /** Separates two passages that share nothing - a chunk boundary is a paragraph boundary. */
  private static final String SEPARATOR = "\n\n";

  /** The marker {@code ChunkLocationResolver} puts in front of a heading path. */
  private static final String SECTION_LOCATION_MARKER = "Abschn. ";

  private PassageText() {}

  /**
   * {@code passages} in order, each appended once: where the tail of what is already written is
   * also the head of the next passage, that shared text is not repeated.
   */
  static String join(List<String> passages) {
    StringBuilder joined = new StringBuilder();
    for (String passage : passages) {
      if (passage == null || passage.isEmpty()) {
        continue;
      }
      if (joined.isEmpty()) {
        joined.append(passage);
        continue;
      }
      int overlap = overlapLength(joined, passage);
      if (overlap > 0) {
        joined.append(passage, overlap, passage.length());
      } else {
        joined.append(SEPARATOR).append(passage);
      }
    }
    return joined.toString();
  }

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

  /** {@code text} cut to {@code limit} characters; the boolean says whether anything was cut. */
  static Truncation truncate(String text, int limit) {
    if (text.length() <= limit) {
      return new Truncation(text, false);
    }
    return new Truncation(text.substring(0, limit), true);
  }

  record Truncation(String text, boolean truncated) {}
}
