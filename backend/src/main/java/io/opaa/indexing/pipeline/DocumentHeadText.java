package io.opaa.indexing.pipeline;

import java.util.List;

/**
 * Builds the {@link DocumentProperties#headText()} of a document: its opening body text, joined
 * from whatever blocks a pipeline has at hand and cut off at {@link
 * DocumentProperties#MAX_HEAD_TEXT_LENGTH} (#1263). Collecting stops as soon as that budget is
 * reached, so a pipeline never materializes a whole document's text just to hand over its head.
 */
public final class DocumentHeadText {

  private DocumentHeadText() {}

  /** The opening of {@code text}; {@code null} for no usable text. */
  public static String of(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    String normalized = text.strip();
    return normalized.length() <= DocumentProperties.MAX_HEAD_TEXT_LENGTH
        ? normalized
        : normalized.substring(0, DocumentProperties.MAX_HEAD_TEXT_LENGTH);
  }

  /** The opening of a {@link HeadingSectionSplitter} event stream, headings included. */
  public static String ofEvents(List<HeadingSectionSplitter.Event> events) {
    StringBuilder head = new StringBuilder();
    for (HeadingSectionSplitter.Event event : events) {
      String text =
          event instanceof HeadingSectionSplitter.Heading heading
              ? heading.title()
              : ((HeadingSectionSplitter.Paragraph) event).text();
      if (text == null || text.isBlank()) {
        continue;
      }
      if (!head.isEmpty()) {
        head.append('\n');
      }
      head.append(text.strip());
      if (head.length() >= DocumentProperties.MAX_HEAD_TEXT_LENGTH) {
        break;
      }
    }
    return of(head.toString());
  }
}
