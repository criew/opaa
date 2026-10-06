package io.opaa.query.citation;

import io.opaa.chat.CitationMarker;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Removes citation markers from a text: all of them on the way into the conversation window
 * (docs/features/conversation-memory.md, "Bauteil 1"), only the malformed ones before an answer is
 * evaluated and stored ({@link CitationMarkerRepair}).
 *
 * <p><b>Only the marker and the whitespace it sat in.</b> The rest of the text comes back character
 * for character: the indentation of a YAML block, the nesting of a list, the two trailing spaces of
 * a hard Markdown line break. The window has to hold what the person read - the next turn is
 * answered against this text.
 *
 * <p><b>Well-formed markers survive persistence.</b> The persisted answer text is the truth for
 * footnotes, text anchors and the evidence drawer; only the copy that becomes conversation history
 * loses them, so a later turn cannot repeat a marker for a document that is no longer in context.
 *
 * <p>Removed, not replaced by a short form: a short form would be imitated by the model as the
 * citation format, and {@link CitationValidator} would then find no marker at all.
 */
public final class CitationMarkers {

  /**
   * A run of one or more markers together with the horizontal whitespace it sits in, and the line
   * end when nothing follows it on that line. Built on {@link CitationMarker#CLAIMED_PATTERN}, so a
   * malformed marker is part of a run like a well-formed one. Matching the surroundings as part of
   * the marker is what keeps the removal local: no pass ever runs over the rest of the text.
   */
  private static final Pattern MARKER_RUN =
      Pattern.compile(
          "(?<run>[ \\t]*(?:"
              + CitationMarker.CLAIMED_PATTERN.pattern()
              + "[ \\t]*)+)(?<lineEnd>\\R|$)?");

  private static final String MARKER = CitationMarker.CLAIMED_PATTERN.pattern();

  private static final String MARKER_LIST =
      "[ \\t]*" + MARKER + "(?:[ \\t]*(?:[,;][ \\t]*)?" + MARKER + ")*[ \\t]*";

  /**
   * Round or square brackets that hold nothing but markers, separated at most by whitespace, a
   * comma or a semicolon. Square brackets directly followed by {@code (} are a Markdown link and
   * stay.
   */
  private static final Pattern BRACKETED_MARKERS =
      Pattern.compile("\\(" + MARKER_LIST + "\\)|\\[" + MARKER_LIST + "\\](?!\\()");

  private CitationMarkers() {}

  /**
   * {@code text} without the brackets the model put around its markers: {@code (【…】)} becomes
   * {@code 【…】}, several markers in one bracket follow each other without separator. Brackets that
   * also hold other text are left alone. A text without such brackets is returned unchanged.
   */
  static String unwrapBracketed(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String result = text;
    String previous;
    // Each pass drops one bracket level, so "((【…】))" needs two; every pass shortens the text.
    do {
      previous = result;
      result = unwrapOnce(previous);
    } while (!result.equals(previous));
    return result.equals(text) ? text : result;
  }

  private static String unwrapOnce(String text) {
    Matcher matcher = BRACKETED_MARKERS.matcher(text);
    StringBuilder result = new StringBuilder(text.length());
    boolean changed = false;
    while (matcher.find()) {
      String markers =
          CitationMarker.CLAIMED_PATTERN
              .matcher(matcher.group())
              .results()
              .map(MatchResult::group)
              .collect(Collectors.joining());
      matcher.appendReplacement(result, Matcher.quoteReplacement(markers));
      changed = true;
    }
    return changed ? matcher.appendTail(result).toString() : text;
  }

  /**
   * {@code text} without anything that presents itself as a citation marker. A marker at the end of
   * a line takes the whitespace before it with it; a marker between two words leaves the single
   * space they were separated by; a marker glued between two characters leaves nothing. A text
   * without a marker is returned unchanged. Brackets that held nothing but markers go with them.
   * {@code null} in, {@code null} out - the caller decides what an answer without text means.
   */
  public static String strip(String text) {
    return strip(unwrapBracketed(text), marker -> true);
  }

  /** {@code text} without its malformed markers; well-formed ones stay where they are. */
  static String stripMalformed(String text) {
    return strip(text, marker -> !CitationMarker.isWellFormed(marker));
  }

  private static String strip(String text, Predicate<String> removed) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    Matcher matcher = MARKER_RUN.matcher(text);
    StringBuilder result = new StringBuilder(text.length());
    int cursor = 0;
    boolean changed = false;
    while (matcher.find()) {
      String run = matcher.group("run");
      List<String> markers =
          CitationMarker.CLAIMED_PATTERN.matcher(run).results().map(MatchResult::group).toList();
      List<String> kept = markers.stream().filter(Predicate.not(removed)).toList();
      if (kept.size() == markers.size()) {
        continue;
      }
      result.append(text, cursor, matcher.start());
      result.append(kept.isEmpty() ? replacementFor(text, matcher) : rebuilt(run, kept, matcher));
      cursor = matcher.end();
      changed = true;
    }
    return changed ? result.append(text, cursor, text.length()).toString() : text;
  }

  /** A run that keeps some of its markers: its own surrounding whitespace, the kept markers. */
  private static String rebuilt(String run, List<String> kept, Matcher matcher) {
    String lineEnd = matcher.group("lineEnd");
    return leadingWhitespaceOf(run)
        + String.join("", kept)
        + trailingWhitespaceOf(run)
        + (lineEnd == null ? "" : lineEnd);
  }

  /**
   * What one marker run leaves behind: the line end it stood before, the line's own indentation at
   * the start of a line, and otherwise a single space if it sat in whitespace at all.
   */
  private static String replacementFor(String text, Matcher matcher) {
    String lineEnd = matcher.group("lineEnd");
    if (lineEnd != null) {
      return lineEnd;
    }
    String run = matcher.group("run");
    if (matcher.start() == 0 || isLineBreak(text.charAt(matcher.start() - 1))) {
      // Whitespace at the start of a line is the line's indentation, not the marker's.
      return leadingWhitespaceOf(run);
    }
    return isSpace(run.charAt(0)) || isSpace(run.charAt(run.length() - 1)) ? " " : "";
  }

  private static String leadingWhitespaceOf(String run) {
    int end = 0;
    while (end < run.length() && isSpace(run.charAt(end))) {
      end++;
    }
    return run.substring(0, end);
  }

  private static String trailingWhitespaceOf(String run) {
    int start = run.length();
    while (start > 0 && isSpace(run.charAt(start - 1))) {
      start--;
    }
    return run.substring(start);
  }

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t';
  }

  private static boolean isLineBreak(char c) {
    return c == '\n' || c == '\r';
  }
}
