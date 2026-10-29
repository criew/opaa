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
    int end = stripEnd(text, 0, text.length());
    int start = stripStart(text, 0, end);
    while (end - start > limit) {
      int cut = cutPosition(text, start, limit);
      int partEnd = stripEnd(text, start, cut);
      if (partEnd > start) {
        parts.add(text.substring(start, partEnd));
      }
      start = stripStart(text, cut, end);
    }
    if (start < end) {
      parts.add(text.substring(start, end));
    }
    return parts;
  }

  /**
   * The end (exclusive) of the next part of {@code text} starting at {@code start}, always within
   * {@code (start, start + limit]}. Positions are absolute, so no remainder is ever copied.
   */
  private static int cutPosition(String text, int start, int limit) {
    int floor = start + limit / 2;
    int window = start + limit;
    for (int i = window - 2; i >= floor; i--) {
      if (text.charAt(i) == '\n' && text.charAt(i + 1) == '\n') {
        return i + 2;
      }
    }
    for (int i = window - 1; i >= floor; i--) {
      if (text.charAt(i) == '\n') {
        return i + 1;
      }
    }
    int sentence = lastMatchEnd(SENTENCE_END, text, floor, window);
    if (sentence > 0) {
      return sentence;
    }
    int space = lastMatchEnd(WHITESPACE, text, floor, window);
    if (space > 0) {
      return space;
    }
    // No boundary at all: a hard cut, never between the two halves of a surrogate pair.
    return limit > 1 && Character.isHighSurrogate(text.charAt(window - 1)) ? window - 1 : window;
  }

  /** The first index in {@code [from, to)} that is not whitespace in the sense of strip, or to. */
  private static int stripStart(String text, int from, int to) {
    int i = from;
    while (i < to) {
      int cp = text.codePointAt(i);
      if (!Character.isWhitespace(cp)) {
        break;
      }
      i += Character.charCount(cp);
    }
    return i;
  }

  /** One past the last index in {@code [from, to)} that is not whitespace, or from. */
  private static int stripEnd(String text, int from, int to) {
    int i = to;
    while (i > from) {
      int cp = text.codePointBefore(i);
      if (!Character.isWhitespace(cp)) {
        break;
      }
      i -= Character.charCount(cp);
    }
    return i;
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
