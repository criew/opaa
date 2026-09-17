package io.opaa.indexing.metadata;

import java.util.Set;
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
 * Beschäftigte" is a document), and the file-name rule is narrowed to a title that is nothing but a
 * file name where the title is a person's subject line - see {@link #matches(String, String)}.
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

  /** A title ending on a document file name - what an export writes instead of a title. */
  private static final Pattern FILE_NAME_TITLE =
      Pattern.compile("(?i)\\.(doc|docx|odt|odp|ods|pdf|rtf|txt|ppt|pptx|xls|xlsx|md|html?)$");

  /** The same, narrowed to a title that is <em>nothing but</em> a file name. */
  private static final Pattern BARE_FILE_NAME_TITLE =
      Pattern.compile("(?i)^\\S+\\.(doc|docx|odt|odp|ods|pdf|rtf|txt|ppt|pptx|xls|xlsx|md|html?)$");

  /** The formats whose title is a person's subject line rather than a tool's output. */
  private static final Set<String> MAIL_EXTENSIONS = Set.of(".eml", ".msg");

  private ToolTitle() {}

  /**
   * Whether {@code title} names the tool or the file rather than the document. A mail's title is
   * its subject, written by a person and regularly naming an attached file ("WG:
   * haushaltsplan-2026.pdf"); there only a title that is <em>nothing but</em> a file name counts.
   * Every other format's title comes from an export, where a file name anywhere in it is one.
   */
  static boolean matches(String title, String formatExtension) {
    if (title == null) {
      return false;
    }
    String stripped = title.strip();
    Matcher toolPrefix = TOOL_PREFIX.matcher(stripped);
    if (toolPrefix.matches() && TOOL_SUBJECT.matcher(toolPrefix.group(1).strip()).matches()) {
      return true;
    }
    Pattern fileNameRule =
        formatExtension != null && MAIL_EXTENSIONS.contains(formatExtension)
            ? BARE_FILE_NAME_TITLE
            : FILE_NAME_TITLE;
    return fileNameRule.matcher(stripped).find();
  }
}
