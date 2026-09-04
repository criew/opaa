package io.opaa.indexing.pipeline;

import java.util.List;

/**
 * Builds the {@link DocumentProperties#titleLine()} of a document: the first non-blank line of its
 * body text, taken from whatever block a pipeline has first at hand (#1289). Only that one line is
 * ever a self-designation of the document; a label line or a quotation below it names another
 * document. The cut to {@link DocumentProperties#MAX_TITLE_LINE_LENGTH} belongs to {@link
 * DocumentProperties}, which enforces it for every source.
 */
public final class DocumentTitleLine {

  private DocumentTitleLine() {}

  /** The first non-blank line of {@code text}; {@code null} for no usable text. */
  public static String of(String text) {
    if (text == null) {
      return null;
    }
    for (String line : text.split("\\R")) {
      String stripped = line.strip();
      if (!stripped.isEmpty()) {
        return stripped;
      }
    }
    return null;
  }

  /** The title line of a {@link HeadingSectionSplitter} event stream, headings included. */
  public static String ofEvents(List<HeadingSectionSplitter.Event> events) {
    for (HeadingSectionSplitter.Event event : events) {
      String text =
          event instanceof HeadingSectionSplitter.Heading heading
              ? heading.title()
              : ((HeadingSectionSplitter.Paragraph) event).text();
      String line = of(text);
      if (line != null) {
        return line;
      }
    }
    return null;
  }
}
