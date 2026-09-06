package io.opaa.indexing.pipeline.office;

import java.util.Set;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

/**
 * The {@code text:h}/{@code text:p} text accumulation every ODF handler shares: {@code
 * text:s}/{@code text:tab}/{@code text:line-break} rendering and the character budget guard. A
 * caller forwards every SAX callback and routes the text {@link #endElement} returns to its own
 * structure (events, slides, header/footer roles).
 *
 * <p>A {@code styles.xml} handler ({@link #forStyles}) never collects text inside a known ODF field
 * element ({@link #FIELD_ELEMENTS}), whose cached value is wrong for every page but the one it was
 * computed on; a {@code content.xml} handler ({@link #forContent}) reads a field's text as body
 * text, since there it is the document's own content.
 */
final class OdfParagraphTextCollector {

  private static final Set<String> FIELD_ELEMENTS =
      Set.of("text:page-number", "text:page-count", "text:date", "text:time");

  private final String subject;
  private final boolean excludeFieldText;
  private final int maxSpaceRepeat;
  private final long maxTextCharacters;
  private long textCharacterCount;

  private int paragraphDepth;
  private int fieldDepth;
  private final StringBuilder text = new StringBuilder();

  private OdfParagraphTextCollector(
      String subject, boolean excludeFieldText, int maxSpaceRepeat, long maxTextCharacters) {
    this.subject = subject;
    this.excludeFieldText = excludeFieldText;
    this.maxSpaceRepeat = maxSpaceRepeat;
    this.maxTextCharacters = maxTextCharacters;
  }

  /** For a document's own body text; {@code subject} names it in a limit's error message. */
  static OdfParagraphTextCollector forContent(
      String subject, int maxSpaceRepeat, long maxTextCharacters) {
    return new OdfParagraphTextCollector(subject, false, maxSpaceRepeat, maxTextCharacters);
  }

  /** For a {@code styles.xml} master page's header/footer text - field values excluded. */
  static OdfParagraphTextCollector forStyles(int maxSpaceRepeat, long maxTextCharacters) {
    return new OdfParagraphTextCollector("ODF styles.xml", true, maxSpaceRepeat, maxTextCharacters);
  }

  /** Whether a {@code text:h}/{@code text:p} is currently open. */
  boolean insideParagraph() {
    return paragraphDepth > 0;
  }

  void startElement(String qName, Attributes attributes) throws SAXException {
    if (excludeFieldText && FIELD_ELEMENTS.contains(qName)) {
      fieldDepth++;
    }
    switch (qName) {
      case "text:h", "text:p" -> {
        if (paragraphDepth == 0) {
          text.setLength(0);
        }
        paragraphDepth++;
      }
      case "text:s" -> appendRepeatedSpace(attributes);
      case "text:tab" -> {
        if (collecting()) {
          text.append('\t');
        }
      }
      case "text:line-break" -> {
        if (collecting()) {
          text.append('\n');
        }
      }
      default -> {
        // Everything else carries no structure this collector renders and is ignored.
      }
    }
  }

  void characters(char[] ch, int start, int length) throws SAXException {
    if (collecting()) {
      checkTextCharacterBudget(length);
      text.append(ch, start, length);
    }
  }

  /**
   * @return the just-closed top-level {@code text:h}/{@code text:p}'s text, or {@code null} when
   *     {@code qName} did not close one
   */
  String endElement(String qName) {
    if (excludeFieldText && FIELD_ELEMENTS.contains(qName) && fieldDepth > 0) {
      fieldDepth--;
    }
    if (!"text:h".equals(qName) && !"text:p".equals(qName)) {
      return null;
    }
    paragraphDepth--;
    return paragraphDepth == 0 ? text.toString() : null;
  }

  private boolean collecting() {
    return paragraphDepth > 0 && fieldDepth == 0;
  }

  private void appendRepeatedSpace(Attributes attributes) throws SAXException {
    if (!collecting()) {
      return;
    }
    int count = parsePositiveIntOrDefault(attributes.getValue("text:c"), 1);
    int repeated = Math.min(count, maxSpaceRepeat);
    checkTextCharacterBudget(repeated);
    text.append(" ".repeat(repeated));
  }

  private void checkTextCharacterBudget(int added) throws SAXException {
    textCharacterCount += added;
    if (textCharacterCount > maxTextCharacters) {
      throw new SAXException(
          subject + " exceeds the configured text character limit of " + maxTextCharacters);
    }
  }

  /** A non-positive or unparseable repeat count is the attribute's own absence. */
  static int parsePositiveIntOrDefault(String value, int defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(value);
      return parsed > 0 ? parsed : defaultValue;
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
