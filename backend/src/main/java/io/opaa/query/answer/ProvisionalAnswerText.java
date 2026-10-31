package io.opaa.query.answer;

import io.opaa.llm.LeadingReasoningText;
import java.util.Optional;

/**
 * The provisional answer while the model streams it: the raw text so far without its leading
 * reasoning text ({@link LeadingReasoningText}), told to the reader as appended text or, where the
 * stripped view no longer extends what was shown - a reasoning block recognised only once its
 * closing tag arrived - as a replacement. Text that may still turn out to open a reasoning block is
 * held back. Not thread-safe; one instance per stream.
 */
final class ProvisionalAnswerText {

  private static final String[] OPENING_TAGS = {"<think>", "<thought>"};

  private final StringBuilder raw = new StringBuilder();
  private String shown = "";

  /** Appends a raw chunk and returns what the reader must change, if anything. */
  Optional<Change> append(String chunk) {
    if (chunk == null || chunk.isEmpty()) {
      return Optional.empty();
    }
    raw.append(chunk);
    String stripped = LeadingReasoningText.strip(raw.toString()).text();
    if (mayStillOpenReasoning(stripped) || stripped.equals(shown)) {
      return Optional.empty();
    }
    Change change =
        stripped.startsWith(shown)
            ? new Change(false, stripped.substring(shown.length()))
            : new Change(true, stripped);
    shown = stripped;
    return Optional.of(change);
  }

  /** The raw text received so far, reasoning included. */
  String raw() {
    return raw.toString();
  }

  private static boolean mayStillOpenReasoning(String text) {
    String start = text.stripLeading();
    if (start.isEmpty()) {
      return true;
    }
    for (String tag : OPENING_TAGS) {
      if (start.length() < tag.length() && tag.startsWith(start)) {
        return true;
      }
    }
    return false;
  }

  /**
   * @param replace {@code true}: {@code text} replaces everything shown so far; {@code false}: it
   *     is appended
   */
  record Change(boolean replace, String text) {}
}
