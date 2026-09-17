package io.opaa.indexing.format.shared;

import java.util.regex.Pattern;

/**
 * Reads a Setext heading at the very start of a plain-text or Markdown document - a line underlined
 * with {@code ===}, the only heading notation a {@code .txt} has.
 *
 * <p>Only a <em>leading</em> one is read: further down, a run of {@code =} is as likely an ASCII
 * rule or a table border, and a heading read from the middle of a document is no self-designation.
 */
public final class SetextHeading {

  /** At least three characters, so a short run inside running text is no underline. */
  private static final Pattern UNDERLINE = Pattern.compile("={3,}");

  private SetextHeading() {}

  /** The document's leading Setext heading, or {@code null} when it opens with anything else. */
  public static String leadingOf(String text) {
    if (text == null) {
      return null;
    }
    String[] lines = text.split("\\R");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      if (line.isEmpty()) {
        continue;
      }
      boolean underlined =
          i + 1 < lines.length && UNDERLINE.matcher(lines[i + 1].strip()).matches();
      return underlined && !UNDERLINE.matcher(line).matches() ? line : null;
    }
    return null;
  }
}
