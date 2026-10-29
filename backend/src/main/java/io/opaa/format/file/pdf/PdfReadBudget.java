package io.opaa.format.file.pdf;

import io.opaa.format.shared.ParserLimitExceededException;
import io.opaa.format.shared.ParserLimitProperties;

/**
 * What one PDF may cost to read: the characters it yields across all pages and the content-stream
 * operators processed for it, counted over every pass (table rulings, table text, flow text) and
 * every nested form. A page is charged with its finished text; while a page is still being
 * stripped, its glyph count is checked against what is left, so a page that would overdraw the
 * budget stops before PDFBox has collected all its glyphs. The operator count bounds CPU time where
 * nested forms draw nothing but still multiply the work. Not thread-safe: one budget belongs to one
 * read of one document.
 */
final class PdfReadBudget {

  private final long maxCharacters;
  private final long maxOperators;
  private long usedCharacters;
  private long operators;

  PdfReadBudget(long maxCharacters, long maxOperators) {
    this.maxCharacters = maxCharacters;
    this.maxOperators = maxOperators;
  }

  static PdfReadBudget of(ParserLimitProperties limits) {
    return new PdfReadBudget(limits.maxTextCharacters(), limits.maxPdfOperators());
  }

  static PdfReadBudget unbounded() {
    return new PdfReadBudget(Long.MAX_VALUE, Long.MAX_VALUE);
  }

  /** Throws when {@code pageGlyphs} more characters would exceed the budget. */
  void requireRoomFor(long pageGlyphs) {
    if (pageGlyphs > maxCharacters - usedCharacters) {
      throw new ParserLimitExceededException(
          "PDF text exceeds the configured text character limit of " + maxCharacters);
    }
  }

  /** Adds a finished page's {@code characters}; throws when the budget is exceeded. */
  void charge(long characters) {
    requireRoomFor(characters);
    usedCharacters += characters;
  }

  /** Counts one processed operator; throws once the document has used up its operators. */
  void countOperator() {
    if (++operators > maxOperators) {
      throw new ParserLimitExceededException(
          "PDF content exceeds the configured limit of " + maxOperators + " operators");
    }
  }
}
