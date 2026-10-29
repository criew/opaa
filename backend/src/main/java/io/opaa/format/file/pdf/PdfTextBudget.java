package io.opaa.format.file.pdf;

import io.opaa.format.shared.ParserLimitExceededException;

/**
 * The characters one PDF may yield across all its pages. A page is charged with its finished text;
 * while a page is still being stripped, its glyph count is checked against what is left, so a page
 * that would overdraw the budget is stopped before PDFBox has collected all its glyphs. Not
 * thread-safe: one budget belongs to one read of one document.
 */
final class PdfTextBudget {

  private final long maxCharacters;
  private long used;

  PdfTextBudget(long maxCharacters) {
    this.maxCharacters = maxCharacters;
  }

  static PdfTextBudget unbounded() {
    return new PdfTextBudget(Long.MAX_VALUE);
  }

  /** Throws when {@code pageGlyphs} more characters would exceed the budget. */
  void requireRoomFor(long pageGlyphs) {
    if (pageGlyphs > maxCharacters - used) {
      throw exceeded();
    }
  }

  /** Adds a finished page's {@code characters}; throws when the budget is exceeded. */
  void charge(long characters) {
    requireRoomFor(characters);
    used += characters;
  }

  private ParserLimitExceededException exceeded() {
    return new ParserLimitExceededException(
        "PDF text exceeds the configured text character limit of " + maxCharacters);
  }
}
