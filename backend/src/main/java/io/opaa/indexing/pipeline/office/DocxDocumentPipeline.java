package io.opaa.indexing.pipeline.office;

import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.DocumentProperties;
import io.opaa.indexing.pipeline.DocumentTitleLine;
import io.opaa.indexing.pipeline.HeadingSectionSplitter;
import io.opaa.indexing.pipeline.RepeatingHeaderChunk;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFieldRun;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFldChar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;
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
 * <p><b>Every header/footer part</b> - {@link XWPFDocument#getHeaderList()}/{@link
 * XWPFDocument#getFooterList()}, the union across every section and every default/first/even
 * variant a multi-section document can carry - becomes one deduplicated leading chunk (location
 * "Kopf-/Fußzeile") rather than being repeated per page or dropped, since none of it is part of
 * {@link XWPFDocument#getBodyElements()}; see {@link RepeatingHeaderChunk}. Two paragraphs whose
 * whitespace-normalized text is equal contribute only once. A field's cached value (e.g. a page
 * number computed the last time the file was saved, correct for at most one page) is excluded
 * rather than indexed as if it were static text.
 *
 * <p><b>Header/footer text never rescues an otherwise body-less document from {@code NO_CONTENT}/
 * {@code NO_EXTRACTABLE_TEXT}.</b> It is template text - present on a scan-only document exactly as
 * much as on one with a text layer - and is therefore no evidence that this document itself carries
 * content; a scanned letter must stay visible as OCR-needing, the single most expensive failure an
 * ingestion pipeline can make (docs/features/ingestion-pipelines.md). The guard is evaluated purely
 * against the body text {@link XWPFDocument#getBodyElements()} yields, before the header/footer
 * chunk is ever added to the result.
 */
public class DocxDocumentPipeline implements DocumentPipeline {

  private static final Logger log = LoggerFactory.getLogger(DocxDocumentPipeline.class);

  static final String ID = "docx";
  static final short VERSION = 3;

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
      return DocumentPipelineResult.parseFailed();
    }
    DocxContent content = readDocxContent(source);
    if (content == null) {
      return DocumentPipelineResult.parseFailed();
    }
    List<HeadingSectionSplitter.Event> events = toEvents(content.bodyElements());
    if (events.isEmpty()) {
      // A genuinely empty document (no body elements at all). Header/footer text never rescues
      // this outcome - see this class's own Javadoc on why the guard ignores it entirely.
      return DocumentPipelineResult.noContent();
    }
    List<Document> chunks =
        new ArrayList<>(HeadingSectionSplitter.chunk(events, MAX_CUTTING_LEVEL));
    if (chunks.isEmpty()) {
      return DocumentPipelineResult.noExtractableText();
    }
    Document headerFooterChunk =
        RepeatingHeaderChunk.ofOrNull(HEADER_FOOTER_LOCATION, content.headerFooterText());
    if (headerFooterChunk != null) {
      chunks.add(0, headerFooterChunk);
    }
    return DocumentPipelineResult.chunked(chunks)
        .withProperties(
            content
                .properties()
                .withFirstHeading(firstTopLevelHeading(events))
                .withTitleLine(DocumentTitleLine.ofEvents(events)));
  }

  /**
   * The OOXML core properties (dc:title, created, modified), the first level-1 heading (ADR-0024)
   * and the opening of the body text (#1263), read without building the chunk stream.
   */
  @Override
  public DocumentProperties readProperties(DocumentPipelineSource source) {
    if (source.file() == null) {
      return DocumentProperties.EMPTY;
    }
    DocxContent content = readDocxContent(source);
    if (content == null) {
      return DocumentProperties.EMPTY;
    }
    List<HeadingSectionSplitter.Event> events = toEvents(content.bodyElements());
    return content
        .properties()
        .withFirstHeading(firstTopLevelHeading(events))
        .withTitleLine(DocumentTitleLine.ofEvents(events));
  }

  private record DocxContent(
      List<IBodyElement> bodyElements, String headerFooterText, DocumentProperties properties) {}

  /** {@code null} when the file could not be opened as a DOCX at all - a parse failure. */
  private static DocxContent readDocxContent(DocumentPipelineSource source) {
    try (InputStream in = Files.newInputStream(source.file())) {
      try (XWPFDocument document = new XWPFDocument(in)) {
        return new DocxContent(
            document.getBodyElements(), headerFooterText(document), coreProperties(document));
      }
    } catch (IOException | RuntimeException e) {
      log.warn("Could not read DOCX document {}", source.fileName(), e);
      return null;
    }
  }

  private static DocumentProperties coreProperties(XWPFDocument document) {
    POIXMLProperties.CoreProperties core = document.getProperties().getCoreProperties();
    return new DocumentProperties(
        core.getTitle(),
        DocumentProperties.toLocalDate(core.getCreated()),
        DocumentProperties.toLocalDate(core.getModified()),
        null,
        null,
        null,
        null,
        false,
        Map.of());
  }

  private static String firstTopLevelHeading(List<HeadingSectionSplitter.Event> events) {
    for (HeadingSectionSplitter.Event event : events) {
      if (event instanceof HeadingSectionSplitter.Heading heading
          && heading.level() == 1
          && !heading.title().isBlank()) {
        return heading.title();
      }
    }
    return null;
  }

  private static String headerFooterText(XWPFDocument document) {
    Map<String, String> headerLines = new LinkedHashMap<>();
    Map<String, String> footerLines = new LinkedHashMap<>();
    for (XWPFHeader header : document.getHeaderList()) {
      collectParagraphLines(header.getParagraphs(), headerLines);
    }
    for (XWPFFooter footer : document.getFooterList()) {
      collectParagraphLines(footer.getParagraphs(), footerLines);
    }
    String header = String.join("\n", headerLines.values());
    String footer = String.join("\n", footerLines.values());
    if (header.isEmpty()) {
      return footer;
    }
    if (footer.isEmpty()) {
      return header;
    }
    return header + "\n\n" + footer;
  }

  /**
   * Adds each paragraph's field-excluding text to {@code lines}, keyed by its whitespace-normalized
   * form so a paragraph repeated verbatim across header/footer variants or sections is kept once.
   */
  private static void collectParagraphLines(
      List<XWPFParagraph> paragraphs, Map<String, String> lines) {
    for (XWPFParagraph paragraph : paragraphs) {
      String stripped = paragraphTextExcludingFieldValues(paragraph).strip();
      if (!stripped.isBlank()) {
        // \\s alone does not match a non-breaking space (U+00A0) or narrow no-break space
        // (U+202F) - both routine in an authority letterhead's column separators - so a variant
        // using one and the default using a plain space would otherwise be treated as distinct
        // lines and both survive deduplication.
        lines.putIfAbsent(stripped.replaceAll("[\\s\\u00A0\\u202F]+", " "), stripped);
      }
    }
  }

  /**
   * A Word complex field (e.g. "Seitenzahl einfügen") is stored as a run sequence: a {@code begin}
   * marker, the field's instruction code ({@code w:instrText}, e.g. {@code " PAGE "} - never
   * content), a {@code separate} marker, then one or more runs holding the field's cached
   * last-computed display value, then an {@code end} marker. The cached value is excluded here - it
   * is correct for at most one page/moment, not document content. Nested fields (a field whose
   * cached value itself contains another field, e.g. {@code IF} wrapping {@code PAGE}) are tracked
   * with a stack of open-field frames rather than a single counter: each {@code BEGIN} pushes an
   * unseparated frame, each {@code SEPARATE} marks the top frame separated, each {@code END} pops
   * it. A run is inside a field's result exactly when the stack holds at least one separated frame
   * - so a nested field with no result part of its own ({@code BEGIN}/{@code instrText}/{@code
   * END}, never updated) pops its own, still-unseparated frame without ending the exclusion of an
   * outer field's separated frame further down the stack. An {@code END} with no open frame is a
   * no-op rather than driving the stack negative; an unbalanced {@code BEGIN}/{@code SEPARATE} with
   * no matching {@code END} can swallow at most the rest of this paragraph, since the stack is
   * local to each call of this method. The mirror case - a {@code SEPARATE} with no open frame,
   * because its {@code BEGIN} was in a previous paragraph - is likewise a no-op rather than an
   * error; the field's cached value that follows is then no longer recognized as inside a result
   * and is included rather than excluded. Accepted: over-collection, not text loss, and a field
   * split across paragraphs is rare in header/footer content.
   *
   * <p>A {@code w:fldSimple} field (LibreOffice's export form, as opposed to Word's begin/separate
   * /end form above) is a distinct POI run type ({@code XWPFFieldRun}) that carries neither {@code
   * w:fldChar} nor {@code w:instrText} on its own {@link org.apache.poi.xwpf.usermodel.XWPFRun
   * #getCTR()} - it is excluded by type rather than by the state machine above.
   *
   * <p>{@link XWPFRun#getText(int)} returns only a run's <em>first</em> {@code w:t} child; a
   * tab-separated multi-column letterhead ("Stadt Musterstadt&lt;tab&gt;Az. 12-34/2026") is
   * routinely one run with several {@code w:t}/{@code w:tab} children, so {@link XWPFRun#text()} is
   * used instead - it renders every child in order, including tabs/breaks as characters, and
   * already excludes {@code w:instrText} itself (POI's own {@code _getText} skips it) - but not
   * {@code w:delText}. A run holding tracked-changes deletion text is therefore excluded by this
   * method's own check ({@code ctr.sizeOfDelTextArray() > 0} below), not by {@link XWPFRun#text()}
   * - the same exclusion {@link XWPFParagraph#getText()} applies to the body.
   */
  private static String paragraphTextExcludingFieldValues(XWPFParagraph paragraph) {
    StringBuilder text = new StringBuilder();
    Deque<Boolean> fieldFrames = new ArrayDeque<>();
    for (XWPFRun run : paragraph.getRuns()) {
      if (run instanceof XWPFFieldRun) {
        continue;
      }
      CTR ctr = run.getCTR();
      for (CTFldChar fldChar : ctr.getFldCharArray()) {
        if (fldChar.getFldCharType() == STFldCharType.BEGIN) {
          fieldFrames.push(Boolean.FALSE);
        } else if (fldChar.getFldCharType() == STFldCharType.SEPARATE && !fieldFrames.isEmpty()) {
          fieldFrames.pop();
          fieldFrames.push(Boolean.TRUE);
        } else if (fldChar.getFldCharType() == STFldCharType.END && !fieldFrames.isEmpty()) {
          fieldFrames.pop();
        }
      }
      boolean insideFieldResult = fieldFrames.contains(Boolean.TRUE);
      if (ctr.sizeOfInstrTextArray() > 0 || ctr.sizeOfDelTextArray() > 0 || insideFieldResult) {
        continue;
      }
      text.append(run.text());
    }
    return text.toString();
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
