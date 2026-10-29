package io.opaa.format.shared;

/**
 * The cut every context a chunk repeats goes through, so a single long one cannot multiply itself
 * by the number of chunks: a heading line, its Fundort and anchor titles to {@link #MAX_LENGTH}; a
 * unit's context - a slide title, a table's context and header line - to {@link
 * HeadingSectionSplitter#contextLimit()}, which grows with the target size.
 */
public final class RepeatedContext {

  /** The longest heading line, Fundort or name a chunk repeats, the ellipsis included. */
  public static final int MAX_LENGTH = 500;

  private RepeatedContext() {}

  /** {@link #cut(String, int)} to {@link #MAX_LENGTH}. */
  public static String cut(String context) {
    return cut(context, MAX_LENGTH);
  }

  /**
   * {@code context} itself up to {@code maxLength} characters, otherwise its start ending in "…",
   * never cut between the two halves of a surrogate pair; {@code null} stays {@code null}.
   */
  public static String cut(String context, int maxLength) {
    if (context == null || context.length() <= maxLength) {
      return context;
    }
    int end = maxLength - 1;
    if (Character.isLowSurrogate(context.charAt(end))) {
      end--;
    }
    return context.substring(0, end) + "…";
  }
}
