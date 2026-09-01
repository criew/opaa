package io.opaa.indexing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * The DOCX pipeline (docs/features/ingestion-pipelines.md, Teil 2: "Markdown, DOCX, HTML |
 * Überschriftenabschnitt"; Teil 1's parsing table: "Tika, ergänzt um Überschriftenebenen aus den
 * Absatzformaten"). Reads a {@code .docx} directly through Apache POI's {@code XWPFDocument}
 * (already a project dependency for {@link TabularDocumentPipeline}'s XLSX reader) rather than
 * through Tika: the paragraph-format heading levels this pipeline cuts on are exactly what Tika's
 * flattened text extraction discards, so a second, format-aware reader is the only way to keep them
 * - Tika is not "ergänzt" after the fact, it is replaced for this one format.
 *
 * <p><b>Heading level comes from the paragraph's own formatting, not from guessed text
 * patterns</b>: a built-in Word heading style (styleId {@code Heading1}..{@code Heading9} in the
 * common English-templated case) <b>or</b> a localized equivalent (LibreOffice and some German Word
 * templates export {@code berschrift1}/{@code Ueberschrift1} - the leading "Ü" is stripped by the
 * OOXML styleId sanitizer, which is why the pattern below matches both spellings; the styleId is
 * <em>not</em> reliably English regardless of authoring locale, contrary to what an earlier version
 * of this Javadoc claimed). Failing both, the paragraph's direct outline level ({@code
 * w:outlineLvl}, the same property Word's own "Gliederungsebene" dropdown sets and a table of
 * contents is built from) is tried. A paragraph with none of the three is body text, folded into
 * the current section - exactly {@link HtmlDocumentPipeline}'s treatment of everything that is not
 * h1-h3.
 *
 * <p><b>Only {@code .docx} is handled, not the legacy binary {@code .doc}</b>: POI's OOXML reader
 * used here cannot open the older binary format at all, and the older {@code HWPFDocument} reader
 * has no equivalent paragraph-style API to extract a heading level from. {@code .doc} keeps running
 * through {@link TikaFallbackPipeline} unchanged.
 *
 * <p><b>Tables are read cell by cell</b>, not skipped: a Gebührenverzeichnis or Formular is
 * practically always a table, and Tika's flattened extraction (the pre-#1061 behaviour) did carry
 * that content, so dropping it here would be a regression, not a simplification. Body elements are
 * walked via {@link XWPFDocument#getBodyElements()} in document order; each {@link XWPFTable}
 * becomes one paragraph-level text block (one line per row, cells joined by {@code " | "}) rather
 * than a heading - a table never itself opens a new section. <b>Known limitation:</b> header/footer
 * paragraphs and tables are not part of {@code getBodyElements()} and are not read by this pipeline
 * (a document that puts content exclusively in a running header/footer loses that content, the same
 * trade-off {@link TabularDocumentPipeline} accepts for a spreadsheet's own formulas).
 */
public class DocxDocumentPipeline implements DocumentPipeline {

  private static final Logger log = LoggerFactory.getLogger(DocxDocumentPipeline.class);

  static final String ID = "docx";
  static final short VERSION = 1;

  /**
   * Cutting stops at level 3, per the Teil 2 table; a deeper heading folds into its section's text.
   */
  private static final int MAX_CUTTING_LEVEL = 3;

  private static final Pattern HEADING_STYLE =
      Pattern.compile("(?i)^(?:heading|berschrift|ueberschrift)[ _]?(\\d)$");

  @Override
  public String id() {
    return ID;
  }

  @Override
  public short version() {
    return VERSION;
  }

  @Override
  public Set<String> handledFormats() {
    return Set.of(".docx");
  }

  @Override
  public DocumentPipelineResult run(DocumentPipelineSource source) {
    if (source.file() == null) {
      // A DOCX pipeline is only ever reached through a genuine .docx file (never RSS-extracted
      // text, ADR-0017 decision 2) - defensive fallback, mirrors PdfDocumentPipeline/
      // PptxDocumentPipeline.
      return DocumentPipelineResult.noContent();
    }
    List<IBodyElement> elements = readBodyElements(source);
    if (elements == null) {
      return DocumentPipelineResult.noContent();
    }
    List<HeadingSectionSplitter.Event> events = toEvents(elements);
    if (events.isEmpty()) {
      return DocumentPipelineResult.noContent();
    }
    List<Document> chunks = HeadingSectionSplitter.chunk(events, MAX_CUTTING_LEVEL);
    if (chunks.isEmpty()) {
      return DocumentPipelineResult.noExtractableText();
    }
    return DocumentPipelineResult.chunked(chunks);
  }

  /** {@code null} when the file could not be opened as a DOCX at all - reported as no content. */
  private static List<IBodyElement> readBodyElements(DocumentPipelineSource source) {
    try (InputStream in = Files.newInputStream(source.file())) {
      try (XWPFDocument document = new XWPFDocument(in)) {
        return document.getBodyElements();
      }
    } catch (IOException | RuntimeException e) {
      log.warn("Could not read DOCX document {}", source.fileName(), e);
      return null;
    }
  }

  private static List<HeadingSectionSplitter.Event> toEvents(List<IBodyElement> elements) {
    List<HeadingSectionSplitter.Event> events = new ArrayList<>();
    for (IBodyElement element : elements) {
      if (element instanceof XWPFParagraph paragraph) {
        String text = paragraph.getText();
        Integer level = headingLevel(paragraph);
        if (level != null) {
          events.add(new HeadingSectionSplitter.Heading(level, text == null ? "" : text));
        } else if (text != null && !text.isBlank()) {
          events.add(new HeadingSectionSplitter.Paragraph(text));
        }
      } else if (element instanceof XWPFTable table) {
        String tableText = tableText(table);
        if (!tableText.isBlank()) {
          events.add(new HeadingSectionSplitter.Paragraph(tableText));
        }
      }
    }
    return events;
  }

  /**
   * One line per row, cells joined by {@code " | "} - a blank row (no non-blank cell) is dropped.
   */
  private static String tableText(XWPFTable table) {
    StringBuilder text = new StringBuilder();
    for (XWPFTableRow row : table.getRows()) {
      StringBuilder rowText = new StringBuilder();
      for (XWPFTableCell cell : row.getTableCells()) {
        String cellText = cell.getText();
        if (cellText != null && !cellText.isBlank()) {
          if (rowText.length() > 0) {
            rowText.append(" | ");
          }
          rowText.append(cellText.strip());
        }
      }
      if (rowText.length() > 0) {
        if (text.length() > 0) {
          text.append('\n');
        }
        text.append(rowText);
      }
    }
    return text.toString();
  }

  private static Integer headingLevel(XWPFParagraph paragraph) {
    String styleId = paragraph.getStyleID();
    if (styleId != null) {
      Matcher matcher = HEADING_STYLE.matcher(styleId);
      if (matcher.matches()) {
        return Integer.parseInt(matcher.group(1));
      }
    }
    try {
      var pPr = paragraph.getCTP().getPPr();
      if (pPr != null && pPr.isSetOutlineLvl()) {
        // w:outlineLvl is zero-based (0 = "Ebene 1"); the same property Word's own
        // "Gliederungsebene" control writes and a table of contents is generated from.
        return pPr.getOutlineLvl().getVal().intValue() + 1;
      }
    } catch (RuntimeException e) {
      // Malformed/unexpected paragraph properties - treat as body text rather than fail the
      // whole document over one paragraph's formatting.
      log.debug("Could not read outline level of a DOCX paragraph", e);
    }
    return null;
  }
}
