package io.opaa.indexing.metadata;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether the title a file format declares names the document or only the tool that wrote it.
 *
 * <p>A printer driver writes its own print style into the PDF Title ("Microsoft Office Outlook -
 * Memo Style"), a Word-to-PDF conversion writes the source file name ("Microsoft Word -
 * vermerk.doc"), and some exports write the bare file name. None of them is a title; a document
 * carrying one falls through to its heading, its title line and finally its file name.
 *
 * <p>Both rules are deliberately narrow, because every false positive costs a correct title: a tool
 * name only counts with a file name or a print style behind it ("Microsoft 365 - Leitfaden für
 * Beschäftigte" is a document), and a file name only counts as a whole title without any further
 * word ("WG: haushaltsplan-2026.pdf" is a mail subject).
 */
final class ToolTitle {

  /** A tool's own signature in front of a dash - the notation every Office print path uses. */
  private static final Pattern TOOL_PREFIX =
      Pattern.compile(
          "(?i)^(?:microsoft|adobe|libreoffice|openoffice|acrobat|pdfcreator|foxit)"
              + "\\b[^\\n-]*-\\s*(.+)$");

  /** What a tool puts behind its own name: the printed file, or the print style it used. */
  private static final Pattern TOOL_SUBJECT =
      Pattern.compile(
          "(?i)^\\[?(?:.*\\.(?:doc|docx|odt|odp|ods|pdf|rtf|txt|ppt|pptx|xls|xlsx)"
              + "|.*\\b(?:style|format|stil|ansicht)"
              + "|(?:dokument|document|pr(?:ä|ae)sentation|presentation|mappe|book|tabelle)\\d*)"
              + "]?$");

  /** A title that is nothing but a document file name, one token and no further word. */
  private static final Pattern FILE_NAME_TITLE =
      Pattern.compile("(?i)^\\S+\\.(doc|docx|odt|odp|ods|pdf|rtf|txt|ppt|pptx|xls|xlsx|md|html?)$");

  private ToolTitle() {}

  /** Whether {@code title} names the tool or the file rather than the document. */
  static boolean matches(String title) {
    if (title == null) {
      return false;
    }
    String stripped = title.strip();
    Matcher toolPrefix = TOOL_PREFIX.matcher(stripped);
    if (toolPrefix.matches() && TOOL_SUBJECT.matcher(toolPrefix.group(1).strip()).matches()) {
      return true;
    }
    return FILE_NAME_TITLE.matcher(stripped).matches();
  }
}
