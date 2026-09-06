package io.opaa.indexing.pipeline;

import java.util.regex.Pattern;

/**
 * The one reading of an ATX heading ({@code ## Titel}), shared by the Markdown pipeline, which cuts
 * on it, and the chunk location resolver, which finds it again inside stored chunk text: group
 * {@link #LEVEL_GROUP} is the hash run whose length is the level, group {@link #TITLE_GROUP} the
 * title without its trailing hashes.
 */
public final class MarkdownHeading {

  public static final int LEVEL_GROUP = 1;
  public static final int TITLE_GROUP = 2;

  private static final String AFTER_INDENT = "(#{1,6})[ \\t]+(\\S.*?)[ \\t#]*$";

  /** A whole line that is a heading - the form a Markdown reader matches line by line. */
  public static final Pattern LINE = Pattern.compile("^ {0,3}" + AFTER_INDENT);

  /**
   * A heading anywhere inside a longer text, with a form feed accepted as a line start alongside a
   * newline - the form stored chunk text is scanned in, where a page break can precede a heading.
   */
  public static final Pattern IN_TEXT = Pattern.compile("(?m)(?:^|\\f)[ \\t]{0,3}" + AFTER_INDENT);

  private MarkdownHeading() {}
}
