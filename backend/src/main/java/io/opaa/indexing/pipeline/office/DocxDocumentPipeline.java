package io.opaa.indexing.pipeline.office;

import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.HeadingSectionSplitter;
import io.opaa.indexing.pipeline.RepeatingHeaderChunk;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeaderFooter;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * The DOCX pipeline (docs/features/ingestion-pipelines.md, Teil 2). Reads a {@code .docx} directly
 * through Apache POI's {@link XWPFDocument} rather than Tika, since Tika's flattened text
 * extraction discards the paragraph-format heading levels this pipeline cuts on.
 *
 * <p>Heading level comes from the paragraph's own formatting: a built-in Word heading style
 * (English or localized, e.g. {@code Ueberschrift1}), falling back to the paragraph's direct
 * outline level ({@code w:outlineLvl}). A paragraph with neither is body text. Cutting stops at
 * level 3 ({@link #MAX_CUTTING_LEVEL}). Tables are read cell by cell into one paragraph-level text
 * block per table (never a heading). Only {@code .docx} is handled - the legacy binary {@code .doc}
 * keeps running through the Tika fallback pipeline.
 *
 * <p><b>The default header/footer</b> (#1145) - not the {@code -left}/{@code -first} variants, to
 * avoid indexing the same text twice - becomes one deduplicated leading chunk (location
 * "Kopf-/Fußzeile") rather than being repeated per page or dropped, since it is not part of {@link
 * XWPFDocument#getBodyElements()}; see {@link RepeatingHeaderChunk}.
 */
public class DocxDocumentPipeline implements DocumentPipeline {

  private static final Logger log = LoggerFactory.getLogger(DocxDocumentPipeline.class);

  static final String ID = "docx";
  static final short VERSION = 2;

  private static final String HEADER_FOOTER_LOCATION = "Kopf-/Fußzeile";

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
    DocxContent content = readDocxContent(source);
    if (content == null) {
      return DocumentPipelineResult.noContent();
    }
    List<HeadingSectionSplitter.Event> events = toEvents(content.bodyElements());
    List<Document> chunks =
        events.isEmpty()
            ? new ArrayList<>()
            : new ArrayList<>(HeadingSectionSplitter.chunk(events, MAX_CUTTING_LEVEL));
    Document headerFooterChunk =
        RepeatingHeaderChunk.ofOrNull(HEADER_FOOTER_LOCATION, content.headerFooterText());
    if (headerFooterChunk != null) {
      chunks.add(0, headerFooterChunk);
    }
    if (events.isEmpty() && headerFooterChunk == null) {
      // A genuinely empty document (no body elements, no header/footer text) - distinct from
      // NO_EXTRACTABLE_TEXT below, which only applies once at least one non-empty event stream
      // itself chunked down to nothing.
      return DocumentPipelineResult.noContent();
    }
    if (chunks.isEmpty()) {
      return DocumentPipelineResult.noExtractableText();
    }
    return DocumentPipelineResult.chunked(chunks);
  }

  private record DocxContent(List<IBodyElement> bodyElements, String headerFooterText) {}

  /** {@code null} when the file could not be opened as a DOCX at all - reported as no content. */
  private static DocxContent readDocxContent(DocumentPipelineSource source) {
    try (InputStream in = Files.newInputStream(source.file())) {
      try (XWPFDocument document = new XWPFDocument(in)) {
        return new DocxContent(document.getBodyElements(), headerFooterText(document));
      }
    } catch (IOException | RuntimeException e) {
      log.warn("Could not read DOCX document {}", source.fileName(), e);
      return null;
    }
  }

  private static String headerFooterText(XWPFDocument document) {
    XWPFHeaderFooterPolicy policy = document.getHeaderFooterPolicy();
    if (policy == null) {
      return "";
    }
    StringBuilder text = new StringBuilder();
    appendHeaderFooterText(text, policy.getDefaultHeader());
    appendHeaderFooterText(text, policy.getDefaultFooter());
    return text.toString();
  }

  private static void appendHeaderFooterText(StringBuilder text, XWPFHeaderFooter part) {
    if (part == null) {
      return;
    }
    String value = part.getText();
    if (value == null || value.isBlank()) {
      return;
    }
    if (!text.isEmpty()) {
      text.append("\n\n");
    }
    text.append(value.strip());
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
