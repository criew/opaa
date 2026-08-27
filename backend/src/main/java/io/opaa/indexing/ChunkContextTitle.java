package io.opaa.indexing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Derives the human-readable title {@link FileProcessingService#chunkEmbedFormatterWithPrefix}
 * prepends to a multi-chunk document's chunk embeddings (#933, "Contextual Chunking"), for a
 * filesystem-style {@code file_name} ({@code "NNN_slug.ext"} - see {@link
 * FileProcessingService#deriveContextTitle} for the RSS-headline/URL exception). Contract: strip a
 * trailing extension ({@code \.[A-Za-z0-9]{1,5}$} - only a suffix that actually looks like one, so
 * a sentence-like name is never truncated at an unrelated period), strip a leading run of purely
 * structural tokens (a numbering scheme like {@code "001_"}, or a short tag-plus-number pair like
 * {@code "city-0022_"}), replace the remaining {@code _}/{@code -} separators with spaces, and cap
 * the result at {@value #MAX_TITLE_TOKENS} tokens.
 *
 * <ul>
 *   <li>{@code "001_personalausweis.md"} → {@code "personalausweis"}
 *   <li>{@code "city-0022_prag.md"} → {@code "prag"}
 *   <li>{@code "report.pdf"} → {@code "report"} (nothing structural to strip)
 * </ul>
 */
final class ChunkContextTitle {

  private static final Pattern SEPARATOR = Pattern.compile("[-_]+");
  private static final Pattern EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{1,5}$");

  // A token is "structural" (part of a numbering scheme, not a word worth embedding) if it is
  // either pure digits, or a short (<=6 char) letters-then-digits run with no separator between
  // them (e.g. "ab123"). A bare short alphabetic tag (e.g. "city") is only structural in
  // combination with the token that follows it - handled separately in deriveTitle, since that
  // decision needs to see the next token too.
  private static final Pattern PURE_DIGITS = Pattern.compile("\\d+");
  private static final Pattern SHORT_LETTERS_THEN_DIGITS = Pattern.compile("[A-Za-z]{1,6}\\d+");
  private static final Pattern SHORT_LETTERS_ONLY = Pattern.compile("[A-Za-z]{1,6}");

  // Keeps the "well under ten tokens" per-chunk budget claim in
  // FileProcessingService#chunkEmbedFormatterWithPrefix's Javadoc true regardless of how many
  // words a file name happens to contain.
  private static final int MAX_TITLE_TOKENS = 8;

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

    List<String> remaining = tokens.subList(dropCount, tokens.size());
    if (remaining.size() > MAX_TITLE_TOKENS) {
      remaining = remaining.subList(0, MAX_TITLE_TOKENS);
    }
    String title = String.join(" ", remaining);
    return title.isBlank() ? baseName : title;
  }

  private static String stripExtension(String fileName) {
    var matcher = EXTENSION.matcher(fileName);
    return matcher.find() ? fileName.substring(0, matcher.start()) : fileName;
  }
}
