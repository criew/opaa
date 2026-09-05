package io.opaa.indexing.pipeline.office;

import java.util.Set;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

/**
 * Shared {@code text:h}/{@code text:p} text accumulation for the {@code styles.xml} handlers of
 * {@link OdtDocumentPipeline} and {@link OdpDocumentPipeline}: {@code text:s}/{@code
 * text:tab}/{@code text:line-break} rendering and the character budget guard. A caller forwards
 * every SAX callback and routes the text {@link #endElement} returns to its own role tracking and
 * deduplication.
 *
 * <p>Text inside a known ODF field element ({@link #FIELD_ELEMENTS}) is never collected - a field's
 * cached value is wrong for every page but the one it was computed on.
 */
final class OdfParagraphTextCollector {

  private static final Set<String> FIELD_ELEMENTS =
      Set.of("text:page-number", "text:page-count", "text:date", "text:time");

  private final int maxSpaceRepeat;
  private final long maxTextCharacters;
  private long textCharacterCount;

  private int paragraphDepth;
  private int fieldDepth;
  private final StringBuilder text = new StringBuilder();

  OdfParagraphTextCollector(int maxSpaceRepeat, long maxTextCharacters) {
    this.maxSpaceRepeat = maxSpaceRepeat;
    this.maxTextCharacters = maxTextCharacters;
  }

  void startElement(String qName, Attributes attributes) throws SAXException {
    if (FIELD_ELEMENTS.contains(qName)) {
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
        if (paragraphDepth > 0 && fieldDepth == 0) {
          text.append('\t');
        }
      }
      case "text:line-break" -> {
        if (paragraphDepth > 0 && fieldDepth == 0) {
          text.append('\n');
        }
      }
      default -> {
        // Everything else carries no structure this collector renders and is ignored.
      }
    }
  }

  void characters(char[] ch, int start, int length) throws SAXException {
    if (paragraphDepth > 0 && fieldDepth == 0) {
      checkTextCharacterBudget(length);
      text.append(ch, start, length);
    }
  }

  /**
   * @return the just-closed top-level {@code text:h}/{@code text:p}'s stripped text, or {@code
   *     null} when {@code qName} did not close one
   */
  String endElement(String qName) {
    if (FIELD_ELEMENTS.contains(qName) && fieldDepth > 0) {
      fieldDepth--;
    }
    if (!"text:h".equals(qName) && !"text:p".equals(qName)) {
      return null;
    }
    paragraphDepth--;
    return paragraphDepth == 0 ? text.toString() : null;
  }

  private void appendRepeatedSpace(Attributes attributes) throws SAXException {
    if (paragraphDepth == 0 || fieldDepth > 0) {
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
          "ODF styles.xml exceeds the configured text character limit of " + maxTextCharacters);
    }
  }

  private static int parsePositiveIntOrDefault(String value, int defaultValue) {
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
