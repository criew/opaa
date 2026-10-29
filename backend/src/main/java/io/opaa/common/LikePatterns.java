package io.opaa.common;

/**
 * {@code LIKE} patterns from typed text. The query binding the pattern declares {@code escape '\'};
 * without the escaping, {@code %%} or {@code __} would match every row.
 */
public final class LikePatterns {

  private LikePatterns() {}

  /** A pattern matching every value that contains {@code text}, each character taken literally. */
  public static String containing(String text) {
    return "%" + escape(text) + "%";
  }

  /** {@code text} with backslash, {@code %} and {@code _} escaped, to be taken literally. */
  public static String escape(String text) {
    return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
