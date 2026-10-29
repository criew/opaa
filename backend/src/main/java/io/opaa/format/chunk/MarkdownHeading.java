package io.opaa.format.chunk;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one reading of an ATX heading ({@code ## Titel}), shared by the Markdown format, which cuts
 * on it, and the chunk location resolver, which finds it again inside stored chunk text: group
 * {@link #LEVEL_GROUP} is the hash run whose length is the level, {@link #title} the title without
 * its trailing hashes. The patterns hold no lazy or nested quantifier, so a match takes linear time
 * in the line; the trailing run is cut in code.
 */
public final class MarkdownHeading {

  public static final int LEVEL_GROUP = 1;
  private static final int TITLE_GROUP = 2;

  private static final String AFTER_INDENT = "(#{1,6})[ \\t]+(\\S.*)$";

  /** A whole line that is a heading - the form a Markdown reader matches line by line. */
  public static final Pattern LINE = Pattern.compile("^ {0,3}" + AFTER_INDENT);

  /**
   * A heading anywhere inside a longer text, with a form feed accepted as a line start alongside a
   * newline - the form stored chunk text is scanned in, where a page break can precede a heading.
   */
  public static final Pattern IN_TEXT = Pattern.compile("(?m)(?:^|\\f)[ \\t]{0,3}" + AFTER_INDENT);

  private MarkdownHeading() {}

  /**
   * The title of a heading {@code matcher} of {@link #LINE} or {@link #IN_TEXT} has just matched:
   * the rest of the line without its trailing run of spaces, tabs and hashes, yet never empty - its
   * first character always stays ({@code # #} has the title "#").
   */
  public static String title(Matcher matcher) {
    String rest = matcher.group(TITLE_GROUP);
    int end = rest.length();
    while (end > 1 && isClosingRun(rest.charAt(end - 1))) {
      end--;
    }
    return rest.substring(0, end);
  }

  private static boolean isClosingRun(char c) {
    return c == ' ' || c == '\t' || c == '#';
  }
}
