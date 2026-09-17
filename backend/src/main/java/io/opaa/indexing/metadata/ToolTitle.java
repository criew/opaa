package io.opaa.indexing.metadata;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Whether the title a file format declares names the document or only the tool that wrote it.
 *
 * <p>A printer driver writes its own print style into the PDF Title ("Microsoft Office Outlook -
 * Memo Style"), a Word-to-PDF conversion writes the source file name ("Microsoft Word -
 * vermerk.doc"), and many exports write the file name verbatim. None of them is a title; a document
 * carrying one falls through to its heading, its title line and finally its file name.
 */
final class ToolTitle {

  /** A tool's own signature in front of a dash - the notation every Office print path uses. */
  private static final Pattern TOOL_PREFIX =
      Pattern.compile(
          "(?i)^(microsoft|adobe|libreoffice|openoffice|acrobat|pdfcreator|foxit)\\b[^\\n-]*-\\s");

  /** A title that is a document file name: an export that copied the name instead of a title. */
  private static final Pattern FILE_NAME_TITLE =
      Pattern.compile("(?i)\\.(doc|docx|odt|odp|ods|pdf|rtf|txt|ppt|pptx|xls|xlsx|md|html?)$");

  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");

  private ToolTitle() {}

  /** Whether {@code title} names the tool or the file rather than the document. */
  static boolean matches(String title, String fileName) {
    if (title == null) {
      return false;
    }
    String stripped = title.strip();
    return TOOL_PREFIX.matcher(stripped).find()
        || FILE_NAME_TITLE.matcher(stripped).find()
        || isFileName(stripped, fileName);
  }

  /**
   * Whether {@code title} is the document's own file name, compared without extension, case and
   * separators - a title that repeats the name adds nothing the file name fallback would not give.
   */
  private static boolean isFileName(String title, String fileName) {
    if (fileName == null || fileName.isBlank()) {
      return false;
    }
    String name = fileName;
    int dot = name.lastIndexOf('.');
    if (dot > 0) {
      name = name.substring(0, dot);
    }
    return normalize(title).equals(normalize(name));
  }

  private static String normalize(String value) {
    return NON_ALPHANUMERIC.matcher(value).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
  }
}
