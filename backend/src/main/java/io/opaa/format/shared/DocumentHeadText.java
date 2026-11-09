package io.opaa.format.shared;

import java.util.List;

/**
 * Builds the {@code DocumentProperties#headText()} of a document: the opening of its body text,
 * collected from whatever blocks a pipeline has at hand until {@link #MAX_LENGTH} characters are
 * together. The cut to that limit belongs to {@code DocumentProperties}, which enforces it for
 * every source.
 */
public final class DocumentHeadText {

  /**
   * Upper bound of the head text, in characters: enough for the opening and the closing provisions
   * of an ordinary administrative document, and small enough that no pipeline pays for the rest of
   * a long one.
   */
  public static final int MAX_LENGTH = 4_000;

  private DocumentHeadText() {}

  /**
   * The opening of a {@link HeadingSectionSplitter} event stream, headings included; only as many
   * characters as the limit allows are copied, however long a single block is.
   */
  public static String ofEvents(List<HeadingSectionSplitter.Event> events) {
    StringBuilder head = new StringBuilder();
    for (HeadingSectionSplitter.Event event : events) {
      int remaining = MAX_LENGTH - head.length();
      if (remaining <= 0) {
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
        remaining--;
      }
      head.append(text, 0, Math.min(text.length(), remaining));
    }
    return head.length() == 0 ? null : head.toString();
  }
}
