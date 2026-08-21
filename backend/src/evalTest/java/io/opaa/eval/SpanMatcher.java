package io.opaa.eval;

import java.util.regex.Pattern;

/**
 * Shared, mild text normalization for locating an {@code answer_span} inside a chunk (issue #721
 * code review, Wichtig 3). Used identically by {@link ChunkAnswerSpanMetrics} (does a returned
 * chunk contain the span?) and {@link ChunkMap} (which chunk of the source document contains the
 * span?) — both must agree on what "contains" means, or a case could resolve for one purpose and
 * not the other without any code change actually being responsible.
 *
 * <p>Collapses runs of whitespace (including newlines) to a single space and trims both strings
 * before comparing. This absorbs the most common, meaningless mismatch — a span copied from
 * rendered text landing on a slightly different line-wrap than the indexed chunk — without papering
 * over a genuinely different or misspelled span. It does <b>not</b> lowercase or strip punctuation:
 * those would risk matching unrelated text and are deliberately out of scope here.
 */
final class SpanMatcher {

  private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

  private SpanMatcher() {}

  /** Whitespace-normalized substring containment, {@code false} for either argument being null. */
  static boolean contains(String haystack, String needle) {
    if (haystack == null || needle == null) {
      return false;
    }
    return normalize(haystack).contains(normalize(needle));
  }

  static String normalize(String text) {
    return WHITESPACE_RUN.matcher(text.strip()).replaceAll(" ");
  }
}
