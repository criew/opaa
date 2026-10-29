package io.opaa.format.shared;

/**
 * The cut every context a chunk repeats goes through: the heading line in front of the chunks of a
 * section and in their Fundort, a slide title or a table's context and header line in front of
 * every part. Such text is written once per chunk, so its length is bounded to {@link #MAX_LENGTH}
 * characters - otherwise a single long heading would multiply itself by the number of chunks.
 */
public final class RepeatedContext {

  /** The longest context a chunk repeats, the ellipsis included. */
  public static final int MAX_LENGTH = 500;

  private RepeatedContext() {}

  /**
   * {@code context} itself up to {@link #MAX_LENGTH} characters, otherwise its start ending in "…",
   * never cut between the two halves of a surrogate pair; {@code null} stays {@code null}.
   */
  public static String cut(String context) {
    if (context == null || context.length() <= MAX_LENGTH) {
      return context;
    }
    int end = MAX_LENGTH - 1;
    if (Character.isLowSurrogate(context.charAt(end))) {
      end--;
    }
    return context.substring(0, end) + "…";
  }
}
