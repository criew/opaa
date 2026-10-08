package io.opaa.format.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cuts a text longer than a limit into consecutive parts of at most that many characters without
 * dropping any of it. Each cut falls on the coarsest boundary found in the second half of the
 * window - a blank line, a line break, a sentence end, a space - and only a run without any of them
 * is cut hard. Parts are stripped; the whitespace at a cut is the only text not carried over.
 */
public final class BoundarySplitter {

  /** A sentence end: terminal punctuation, optionally a closing quote/bracket, then whitespace. */
  private static final Pattern SENTENCE_END = Pattern.compile("[.!?…][\"'”»)\\]]?\\s+(?=\\p{Lu})");

  private static final Pattern WHITESPACE = Pattern.compile("\\s");

  private BoundarySplitter() {}

  /** {@code text} stripped as a single part when it fits {@code limit}, otherwise several parts. */
  public static List<String> split(String text, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive, got " + limit);
    }
    List<String> parts = new ArrayList<>();
    String rest = text.strip();
    while (rest.length() > limit) {
      int cut = cutPosition(rest, limit);
      String part = rest.substring(0, cut).strip();
      if (!part.isEmpty()) {
        parts.add(part);
      }
      rest = rest.substring(cut).strip();
    }
    if (!rest.isEmpty()) {
      parts.add(rest);
    }
    return parts;
  }

  /** The end (exclusive) of the next part of {@code text}, always within {@code (0, limit]}. */
  private static int cutPosition(String text, int limit) {
    int floor = limit / 2;
    int paragraph = text.lastIndexOf("\n\n", limit - 2);
    if (paragraph >= floor) {
      return paragraph + 2;
    }
    int line = text.lastIndexOf('\n', limit - 1);
    if (line >= floor) {
      return line + 1;
    }
    int sentence = lastMatchEnd(SENTENCE_END, text, floor, limit);
    if (sentence > 0) {
      return sentence;
    }
    int space = lastMatchEnd(WHITESPACE, text, floor, limit);
    if (space > 0) {
      return space;
    }
    // No boundary at all: a hard cut, never between the two halves of a surrogate pair.
    return limit > 1 && Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
  }

  /** The end of the last match of {@code pattern} ending within {@code [from, to]}, or -1. */
  private static int lastMatchEnd(Pattern pattern, String text, int from, int to) {
    Matcher matcher = pattern.matcher(text).region(from, to).useTransparentBounds(true);
    int end = -1;
    while (matcher.find()) {
      end = matcher.end();
    }
    return end;
  }
}
