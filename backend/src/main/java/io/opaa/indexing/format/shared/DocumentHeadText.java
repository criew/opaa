package io.opaa.indexing.format.shared;

import io.opaa.indexing.format.DocumentProperties;
import java.util.List;

/**
 * Builds the {@link DocumentProperties#headText()} of a document: the opening of its body text,
 * collected from whatever blocks a pipeline has at hand until {@link
 * DocumentProperties#MAX_HEAD_TEXT_LENGTH} characters are together. The cut to that limit belongs
 * to {@link DocumentProperties}, which enforces it for every source.
 */
public final class DocumentHeadText {

  private DocumentHeadText() {}

  /** The opening of a {@link HeadingSectionSplitter} event stream, headings included. */
  public static String ofEvents(List<HeadingSectionSplitter.Event> events) {
    StringBuilder head = new StringBuilder();
    for (HeadingSectionSplitter.Event event : events) {
      if (head.length() >= DocumentProperties.MAX_HEAD_TEXT_LENGTH) {
        break;
      }
      String text =
          event instanceof HeadingSectionSplitter.Heading heading
              ? heading.title()
              : ((HeadingSectionSplitter.Paragraph) event).text();
      if (text == null || text.isBlank()) {
        continue;
      }
      if (head.length() > 0) {
        head.append('\n');
      }
      head.append(text);
    }
    return head.length() == 0 ? null : head.toString();
  }
}
