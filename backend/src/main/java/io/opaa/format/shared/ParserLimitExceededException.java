package io.opaa.format.shared;

/**
 * A document crossed one of the configured parser ceilings ({@link ParserLimitProperties}).
 * Unchecked on purpose: it is raised from inside library callbacks (a PDFBox text stripper) that
 * swallow checked exceptions of some operators, and {@code DocumentFormatRunner} maps it to a parse
 * failure like any other exception out of a format.
 */
public final class ParserLimitExceededException extends RuntimeException {

  public ParserLimitExceededException(String message) {
    super(message);
  }

  public static ParserLimitExceededException textCharacters(
      String subject, long maxTextCharacters) {
    return new ParserLimitExceededException(
        subject + " exceeds the configured text character limit of " + maxTextCharacters);
  }
}
