package io.opaa.query.citation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes citation markers from a text on its way into the conversation window
 * (docs/features/conversation-memory.md, "Bauteil 1").
 *
 * <p><b>Only the marker and the whitespace it sat in.</b> The rest of the text comes back character
 * for character: the indentation of a YAML block, the nesting of a list, the two trailing spaces of
 * a hard Markdown line break. The window has to hold what the person read - the next turn is
 * answered against this text.
 *
 * <p><b>Never applied before persistence.</b> The persisted answer text is the truth for footnotes,
 * text anchors and the evidence drawer; only the copy that becomes conversation history loses its
 * markers, so a later turn cannot repeat a marker for a document that is no longer in context.
 *
 * <p>Removed, not replaced by a short form: a short form would be imitated by the model as the
 * citation format, and {@link CitationValidator} would then find no marker at all.
 */
public final class CitationMarkers {

  /**
   * A run of one or more markers together with the horizontal whitespace it sits in, and the line
   * end when nothing follows it on that line. Built from {@link CitationParser#CITATION_PATTERN} so
   * there stays exactly one definition of what a marker is. Matching the surroundings as part of
   * the marker is what keeps the removal local: no pass ever runs over the rest of the text.
   */
  private static final Pattern MARKER_RUN =
      Pattern.compile(
          "(?<run>[ \\t]*(?:"
              + CitationParser.CITATION_PATTERN.pattern()
              + "[ \\t]*)+)(?<lineEnd>\\R|$)?");

  private CitationMarkers() {}

  /**
   * {@code text} without its citation markers. A marker at the end of a line takes the whitespace
   * before it with it; a marker between two words leaves the single space they were separated by; a
   * marker glued between two characters leaves nothing. A text without a marker is returned
   * unchanged. {@code null} in, {@code null} out - the caller decides what an answer without text
   * means.
   */
  public static String strip(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    Matcher matcher = MARKER_RUN.matcher(text);
    StringBuilder result = new StringBuilder(text.length());
    int cursor = 0;
    while (matcher.find()) {
      result.append(text, cursor, matcher.start());
      result.append(replacementFor(text, matcher));
      cursor = matcher.end();
    }
    return cursor == 0 ? text : result.append(text, cursor, text.length()).toString();
  }

  /**
   * What one marker run leaves behind: the line end it stood before, nothing at the start of a
   * line, and otherwise a single space if it sat in whitespace at all.
   */
  private static String replacementFor(String text, Matcher matcher) {
    String lineEnd = matcher.group("lineEnd");
    if (lineEnd != null) {
      return lineEnd;
    }
    if (matcher.start() == 0 || isLineBreak(text.charAt(matcher.start() - 1))) {
      return "";
    }
    String run = matcher.group("run");
    return isSpace(run.charAt(0)) || isSpace(run.charAt(run.length() - 1)) ? " " : "";
  }

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t';
  }

  private static boolean isLineBreak(char c) {
    return c == '\n' || c == '\r';
  }
}
