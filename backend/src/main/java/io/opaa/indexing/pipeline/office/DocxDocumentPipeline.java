package io.opaa.indexing.pipeline.office;

import io.opaa.indexing.pipeline.DeduplicatedLines;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.DocumentProperties;
import io.opaa.indexing.pipeline.DocumentTitleLine;
import io.opaa.indexing.pipeline.FileDocumentPipeline;
import io.opaa.indexing.pipeline.HeadingSectionSplitter;
import io.opaa.indexing.pipeline.RepeatingHeaderChunk;
import io.opaa.indexing.pipeline.TableText;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
 * The DOCX pipeline (ingestion-pipelines.md, Teil 2), reading {@code .docx} through POI's {@link
 * XWPFDocument} rather than Tika, whose flattened extraction discards the heading levels this
 * pipeline cuts on. Heading level comes from a built-in Word heading style (English or localized)
 * or its own {@code w:outlineLvl}; cutting stops at {@link #MAX_CUTTING_LEVEL}, a table becomes one
 * text block, and the legacy binary {@code .doc} stays with the Tika fallback.
 *
 * <p>Every header/footer part becomes one deduplicated leading chunk (see {@link
 * RepeatingHeaderChunk}), since none is part of {@link XWPFDocument#getBodyElements()}; a field's
 * cached value is excluded rather than indexed as static text. That chunk never rescues a body-less
 * document from {@code NO_CONTENT}/{@code NO_EXTRACTABLE_TEXT} - a scan must stay OCR-needing.
 */
public class DocxDocumentPipeline extends FileDocumentPipeline<DocxDocumentPipeline.DocxContent> {

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

  /**
   * One DOCX's reading, as plain data outliving the {@link XWPFDocument}: the body's event stream,
   * the deduplicated header/footer text and the OOXML core properties.
   */
  public record DocxContent(
      List<HeadingSectionSplitter.Event> events,
      String headerFooterText,
      DocumentProperties coreProperties) {}

  @Override
  protected DocxContent read(DocumentPipelineSource source) throws IOException {
    try (InputStream in = Files.newInputStream(source.file());
        XWPFDocument document = new XWPFDocument(in)) {
      return new DocxContent(
          toEvents(document.getBodyElements()),
          headerFooterText(document),
          coreProperties(document));
    }
  }

  @Override
  protected DocumentPipelineResult chunks(DocumentPipelineSource source, DocxContent content) {
    if (content.events().isEmpty()) {
      // A genuinely empty document (no body elements at all). Header/footer text never rescues
      // this outcome - see this class's own Javadoc on why the guard ignores it entirely.
      return DocumentPipelineResult.noContent();
    }
    List<Document> chunks =
        new ArrayList<>(HeadingSectionSplitter.chunk(content.events(), MAX_CUTTING_LEVEL));
    if (chunks.isEmpty()) {
      return DocumentPipelineResult.noExtractableText();
    }
    Document headerFooterChunk =
        RepeatingHeaderChunk.ofOrNull(HEADER_FOOTER_LOCATION, content.headerFooterText());
    if (headerFooterChunk != null) {
      chunks.add(0, headerFooterChunk);
    }
    return DocumentPipelineResult.chunked(chunks);
  }

  /**
   * The OOXML core properties (dc:title, created, modified), the first level-1 heading (ADR-0024)
   * and the opening of the body text.
   */
  @Override
  protected DocumentProperties properties(DocxContent content) {
    return content
        .coreProperties()
        .withFirstHeading(HeadingSectionSplitter.firstTopLevelHeading(content.events()))
        .withTitleLine(DocumentTitleLine.ofEvents(content.events()));
  }

  private static DocumentProperties coreProperties(XWPFDocument document) {
    POIXMLProperties.CoreProperties core = document.getProperties().getCoreProperties();
    return DocumentProperties.builder()
        .title(core.getTitle())
        .createdAt(DocumentProperties.toLocalDate(core.getCreated()))
        .modifiedAt(DocumentProperties.toLocalDate(core.getModified()))
        .build();
  }

  private static String headerFooterText(XWPFDocument document) {
    DeduplicatedLines headerLines = new DeduplicatedLines();
    DeduplicatedLines footerLines = new DeduplicatedLines();
    for (XWPFHeader header : document.getHeaderList()) {
      collectParagraphLines(header.getParagraphs(), headerLines);
    }
    for (XWPFFooter footer : document.getFooterList()) {
      collectParagraphLines(footer.getParagraphs(), footerLines);
    }
    if (headerLines.isEmpty()) {
      return footerLines.text();
    }
    if (footerLines.isEmpty()) {
      return headerLines.text();
    }
    return headerLines.text() + "\n\n" + footerLines.text();
  }

  /**
   * Adds each paragraph's field-excluding text to {@code lines}, so a paragraph repeated verbatim
   * across header/footer variants or sections is kept once.
   */
  private static void collectParagraphLines(
      List<XWPFParagraph> paragraphs, DeduplicatedLines lines) {
    for (XWPFParagraph paragraph : paragraphs) {
      lines.add(paragraphTextExcludingFieldValues(paragraph));
    }
  }

  /**
   * The paragraph's text without any complex field's cached display value - that value is correct
   * for at most one page or moment, not document content. Nested fields are tracked with a stack of
   * open-field frames: a run is inside a result exactly when the stack holds at least one separated
   * frame, so an inner field with no result of its own does not end an outer field's exclusion. An
   * unbalanced marker is a no-op, never an error; the stack is local to this call, so the worst
   * case is over-collection within one paragraph, never text loss. A {@code w:fldSimple} field
   * (LibreOffice's export form) is excluded by run type instead.
   *
   * <p>Uses {@link XWPFRun#text()}, not {@link XWPFRun#getText(int)}: the latter returns only a
   * run's first {@code w:t} child, while a tab-separated letterhead is routinely one run with
   * several. It already skips {@code w:instrText} but not {@code w:delText}, so tracked-changes
   * deletions are excluded by this method's own check below.
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

  /** One line per row via {@link TableText}; a blank cell is padding, a blank row is dropped. */
  private static String tableText(XWPFTable table) {
    List<List<String>> rows = new ArrayList<>();
    for (XWPFTableRow row : table.getRows()) {
      List<String> cells = new ArrayList<>();
      for (XWPFTableCell cell : row.getTableCells()) {
        cells.add(cell.getText());
      }
      rows.add(cells);
    }
    return TableText.rowsOfNonBlankCells(rows);
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
