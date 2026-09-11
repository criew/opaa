package io.opaa.query.citation;

import java.util.regex.Pattern;

/**
 * Removes citation markers from a text on its way into the conversation window
 * (docs/features/conversation-memory.md, "Bauteil 1").
 *
 * <p><b>Never applied before persistence.</b> The persisted answer text is the truth for footnotes,
 * text anchors and the evidence drawer; only the copy that becomes conversation history loses its
 * markers, so a later turn cannot repeat a marker for a document that is no longer in context.
 *
 * <p>Removed, not replaced by a short form: a short form would be imitated by the model as the
 * citation format, and {@link CitationValidator} would then find no marker at all.
 */
public final class CitationMarkers {

  /** Whitespace left behind where a marker stood, up to the end of its line. */
  private static final Pattern TRAILING_SPACE = Pattern.compile("[ \\t]+(?=\\R|$)");

  /** Two or more spaces left behind by a marker removed mid-sentence. */
  private static final Pattern REPEATED_SPACE = Pattern.compile("[ \\t]{2,}");

  private CitationMarkers() {}

  /**
   * {@code text} without its citation markers and without the whitespace they leave behind. {@code
   * null} in, {@code null} out - the caller decides what an answer without text means.
   */
  public static String strip(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String withoutMarkers = CitationParser.CITATION_PATTERN.matcher(text).replaceAll("");
    String collapsed = REPEATED_SPACE.matcher(withoutMarkers).replaceAll(" ");
    return TRAILING_SPACE.matcher(collapsed).replaceAll("").strip();
  }
}
