package io.opaa.indexing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Derives the human-readable title {@link FileProcessingService#CHUNK_EMBED_CONTENT_FORMATTER}
 * prepends to every chunk's embedding (#933, "Contextual Chunking"). Contract: strip the file
 * extension, strip a leading run of purely structural tokens (a numbering scheme like {@code
 * "001_"}, {@code "07_"}, or a short tag-plus-number pair like {@code "city-0022_"}), then replace
 * the remaining {@code _}/{@code -} separators with spaces. Deliberately filename-only - it does
 * not read document content (a first heading, Tika's {@code dc:title}) even though that would often
 * produce a better title, to keep the contract a pure, deterministic function of the file name
 * alone; see {@link FileProcessingService#CHUNK_EMBED_CONTENT_FORMATTER}'s Javadoc for why that
 * scope was chosen for #933.
 *
 * <ul>
 *   <li>{@code "001_personalausweis.md"} → {@code "personalausweis"}
 *   <li>{@code "01_verwaltungsgebuehrensatzung.pdf"} → {@code "verwaltungsgebuehrensatzung"}
 *   <li>{@code "city-0022_prag.md"} → {@code "prag"}
 *   <li>{@code "report.pdf"} → {@code "report"} (nothing structural to strip)
 * </ul>
 */
final class ChunkContextTitle {

  private static final Pattern SEPARATOR = Pattern.compile("[-_]+");

  // A token is "structural" (part of a numbering scheme, not a word worth embedding) if it is
  // either pure digits, or a short (<=6 char) letters-then-digits run with no separator between
  // them (e.g. "ab123"). A bare short alphabetic tag (e.g. "city") is only structural in
  // combination with the token that follows it - handled separately in deriveTitle, since that
  // decision needs to see the next token too.
  private static final Pattern PURE_DIGITS = Pattern.compile("\\d+");
  private static final Pattern SHORT_LETTERS_THEN_DIGITS = Pattern.compile("[A-Za-z]{1,6}\\d+");
  private static final Pattern SHORT_LETTERS_ONLY = Pattern.compile("[A-Za-z]{1,6}");

  private ChunkContextTitle() {}

  /**
   * @param fileName the chunk's {@code file_name} metadata value, extension included
   * @return the derived title, never blank - falls back to the extension-stripped file name
   *     unchanged if stripping structural tokens would leave nothing (e.g. a file named just {@code
   *     "12345.pdf"})
   */
  static String deriveTitle(String fileName) {
    String baseName = stripExtension(fileName);
    List<String> tokens = new ArrayList<>(List.of(SEPARATOR.split(baseName)));
    tokens.removeIf(String::isBlank);
    if (tokens.isEmpty()) {
      return baseName;
    }

    int dropCount = 0;
    while (dropCount < tokens.size() - 1) {
      String token = tokens.get(dropCount);
      boolean structuralAlone =
          PURE_DIGITS.matcher(token).matches()
              || SHORT_LETTERS_THEN_DIGITS.matcher(token).matches();
      boolean structuralTagBeforeNumber =
          SHORT_LETTERS_ONLY.matcher(token).matches()
              && PURE_DIGITS.matcher(tokens.get(dropCount + 1)).matches();
      if (!structuralAlone && !structuralTagBeforeNumber) {
        break;
      }
      dropCount++;
    }

    String title = String.join(" ", tokens.subList(dropCount, tokens.size()));
    return title.isBlank() ? baseName : title;
  }

  private static String stripExtension(String fileName) {
    int lastDot = fileName.lastIndexOf('.');
    return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
  }
}
