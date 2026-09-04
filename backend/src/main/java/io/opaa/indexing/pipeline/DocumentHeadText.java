package io.opaa.indexing.pipeline;

import java.util.List;

/**
 * Builds the {@link DocumentProperties#headText()} of a document: its opening body text, joined
 * from whatever blocks a pipeline has at hand (#1263). Collecting stops as soon as {@link
 * DocumentProperties#MAX_HEAD_TEXT_LENGTH} is reached, so no more blocks are appended than needed -
 * a single block can still be larger than the budget. The cut itself belongs to {@link
 * DocumentProperties}, which enforces the limit for every source.
 */
public final class DocumentHeadText {

  private DocumentHeadText() {}

  /** The opening of {@code text}; {@code null} for no usable text. */
  public static String of(String text) {
    return text == null || text.isBlank() ? null : text.strip();
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
