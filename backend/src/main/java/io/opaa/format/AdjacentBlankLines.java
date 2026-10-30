package io.opaa.format;

import java.util.regex.Pattern;

/**
 * Collapses runs of blank lines in extracted text with exactly the result of Spring AI's {@code
 * ExtractedTextFormatter.defaults().format}, in linear time and constant stack depth: a line of
 * spaces only is emptied, then {@code (?m)^$([\r\n]+?)(^$[\r\n]+?^)+} is replaced by its first
 * group. The second expression is evaluated by hand, since the regex engine recurses once per blank
 * line and overflows the stack on a long run.
 */
final class AdjacentBlankLines {

  private static final Pattern SPACES_ONLY_LINE = Pattern.compile("(?m)^ *\n");

  private AdjacentBlankLines() {}

  static String trim(String text) {
    String s = SPACES_ONLY_LINE.matcher(text).replaceAll("\n");
    StringBuilder out = null;
    int copied = 0;
    int i = 0;
    while (i < s.length()) {
      int groupEnd = firstGroupEnd(s, i);
      if (groupEnd < 0) {
        i++;
        continue;
      }
      int end = groupEnd;
      for (int next = blankLine(s, end); next >= 0; next = blankLine(s, end)) {
        end = next;
      }
      if (out == null) {
        out = new StringBuilder(s.length());
      }
      out.append(s, copied, groupEnd);
      copied = end;
      i = end;
    }
    return out == null ? s : out.append(s, copied, s.length()).toString();
  }

  /** The end of {@code ^$([\r\n]+?)} at {@code start} that one blank line follows, or -1. */
  private static int firstGroupEnd(String s, int start) {
    if (!lineStart(s, start) || !lineEnd(s, start)) {
      return -1;
    }
    for (int end = start; end < s.length() && isCrOrLf(s.charAt(end)); ) {
      end++;
      if (blankLine(s, end) >= 0) {
        return end;
      }
    }
    return -1;
  }

  /** The end of one {@code ^$[\r\n]+?^} at {@code start}, or -1. */
  private static int blankLine(String s, int start) {
    if (!lineStart(s, start) || !lineEnd(s, start)) {
      return -1;
    }
    for (int end = start; end < s.length() && isCrOrLf(s.charAt(end)); ) {
      end++;
      if (lineStart(s, end)) {
        return end;
      }
    }
    return -1;
  }

  /** {@code ^} in MULTILINE mode: never at the end of input, never between CR and LF. */
  private static boolean lineStart(String s, int i) {
    if (i >= s.length()) {
      return false;
    }
    if (i == 0) {
      return true;
    }
    char before = s.charAt(i - 1);
    return isTerminator(before) && !(before == '\r' && s.charAt(i) == '\n');
  }

  /** {@code $} in MULTILINE mode: at the end of input or before a line terminator. */
  private static boolean lineEnd(String s, int i) {
    if (i >= s.length()) {
      return true;
    }
    char c = s.charAt(i);
    if (c == '\n') {
      return !(i > 0 && s.charAt(i - 1) == '\r');
    }
    return isTerminator(c);
  }

  private static boolean isCrOrLf(char c) {
    return c == '\r' || c == '\n';
  }

  private static boolean isTerminator(char c) {
    return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
  }
}
