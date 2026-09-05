package io.opaa.indexing.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Collects the lines of repeating page furniture (a DOCX header/footer, an ODF master page) and
 * keeps each of them once, in first-seen order. Two lines are the same when their {@link
 * Whitespace#normalize normalized} form is equal, so a variant that separates its columns with a
 * non-breaking space and one that uses a plain space do not both survive.
 */
public final class DeduplicatedLines {

  // Normalized line -> first-seen original line, insertion-ordered so the rendered text keeps the
  // order the lines appeared in.
  private final Map<String, String> lines = new LinkedHashMap<>();

  /** Adds {@code line} stripped, unless it is blank or a variant of one already collected. */
  public void add(String line) {
    if (line == null) {
      return;
    }
    String stripped = line.strip();
    if (stripped.isBlank()) {
      return;
    }
    lines.putIfAbsent(Whitespace.normalize(stripped), stripped);
  }

  public boolean isEmpty() {
    return lines.isEmpty();
  }

  /** The collected lines, line-break separated. */
  public String text() {
    return String.join("\n", lines.values());
  }
}
