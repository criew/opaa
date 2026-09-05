package io.opaa.indexing.pipeline.office;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.DocumentProperties;
import io.opaa.indexing.pipeline.FileDocumentPipeline;
import io.opaa.indexing.pipeline.HeadingSectionSplitter;
import io.opaa.indexing.pipeline.TableText;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.ai.document.Document;

/**
 * The PPTX pipeline (ingestion-pipelines.md, Teil 2: eine Folie = ein Chunk), reading through POI's
 * {@link XMLSlideShow}, since no framework returns a per-slide document and Tika flattens every
 * slide into one block. Every slide with text becomes exactly one chunk, a heading-only slide
 * included, so the slide numbering a citation relies on stays contiguous; a presentation whose
 * slides carry no text at all is rejected as {@code NO_EXTRACTABLE_TEXT}.
 *
 * <p>The title placeholder becomes the chunk's leading line and its {@link
 * ChunkingService#LOCATION_METADATA_KEY location}, every other shape's text follows in shape order,
 * and speaker notes are appended as a final labeled paragraph.
 */
public class PptxDocumentPipeline extends FileDocumentPipeline<PptxDocumentPipeline.PptxContent> {

  static final String ID = "pptx";
  static final short VERSION = 1;

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
    return Set.of(".pptx");
  }

  /**
   * One presentation's reading: one entry per slide in slide order, plus the OOXML core properties
   * and the first slide's title as the first heading (ADR-0024).
   */
  public record PptxContent(List<SlideChunk> slides, DocumentProperties properties) {}

  @Override
  protected PptxContent read(DocumentPipelineSource source) throws IOException {
    try (InputStream in = Files.newInputStream(source.file());
        XMLSlideShow slideShow = new XMLSlideShow(in)) {
      List<XSLFSlide> slides = slideShow.getSlides();
      List<SlideChunk> built = new ArrayList<>(slides.size());
      for (int i = 0; i < slides.size(); i++) {
        built.add(buildChunk(slides.get(i), i + 1));
      }
      POIXMLProperties.CoreProperties core = slideShow.getProperties().getCoreProperties();
      String firstHeading = slides.isEmpty() ? null : titleText(titleShape(slides.getFirst()));
      return new PptxContent(
          built,
          DocumentProperties.builder()
              .title(core.getTitle())
              .createdAt(DocumentProperties.toLocalDate(core.getCreated()))
              .modifiedAt(DocumentProperties.toLocalDate(core.getModified()))
              .firstHeading(firstHeading)
              .build());
    }
  }

  @Override
  protected DocumentPipelineResult chunks(DocumentPipelineSource source, PptxContent content) {
    if (content.slides().isEmpty()) {
      return DocumentPipelineResult.noContent();
    }
    if (content.slides().stream().noneMatch(SlideChunk::hasText)) {
      return DocumentPipelineResult.noExtractableText();
    }
    return DocumentPipelineResult.chunked(
        content.slides().stream().map(SlideChunk::document).toList());
  }

  @Override
  protected DocumentProperties properties(PptxContent content) {
    return content.properties();
  }

  /**
   * @param hasText whether the slide carried any real text - see this class's own Javadoc.
   */
  public record SlideChunk(Document document, boolean hasText) {}

  private static SlideChunk buildChunk(XSLFSlide slide, int slideNumber) {
    XSLFShape titleShape = titleShape(slide);
    String title = titleText(titleShape);
    StringBuilder body = new StringBuilder();
    collectShapeText(slide.getShapes(), titleShape, body);
    boolean hasBodyText = body.length() > 0;
    String notes = notesText(slide);
    if (notes != null) {
      appendParagraph(body, "Notizen: " + notes);
    }
    String location = "Folie " + slideNumber + (title == null ? "" : ": " + title);
    String text =
        title == null ? body.toString() : body.length() == 0 ? title : title + "\n\n" + body;
    boolean hasText = title != null || hasBodyText || notes != null;
    if (text.isBlank()) {
      text = location;
    }
    Map<String, Object> metadata = new HashMap<>();
    metadata.put(ChunkingService.LOCATION_METADATA_KEY, location);
    return new SlideChunk(
        new Document(HeadingSectionSplitter.capChunkLength(text), metadata), hasText);
  }

  /**
   * Walks {@code shapes} in order, descending into a {@link XSLFGroupShape} recursively and reading
   * a {@link XSLFTable} row by row - neither is a {@link XSLFTextShape}, so a naive {@code
   * instanceof} filter would silently drop both. {@code titleShape} is skipped by object identity,
   * not by comparing text: a body shape repeating the title's exact wording must still be read.
   */
  private static void collectShapeText(
      List<XSLFShape> shapes, XSLFShape titleShape, StringBuilder body) {
    for (XSLFShape shape : shapes) {
      if (shape == titleShape) {
        continue;
      }
      if (shape instanceof XSLFGroupShape group) {
        collectShapeText(group.getShapes(), titleShape, body);
      } else if (shape instanceof XSLFTable table) {
        String tableText = tableText(table);
        if (!tableText.isBlank()) {
          appendParagraph(body, tableText);
        }
      } else if (shape instanceof XSLFTextShape textShape) {
        String text = textShape.getText();
        if (text != null && !text.isBlank()) {
          appendParagraph(body, text.strip());
        }
      }
    }
  }

  /** One line per row via {@link TableText}, mirroring {@link DocxDocumentPipeline}. */
  private static String tableText(XSLFTable table) {
    List<List<String>> rows = new ArrayList<>();
    for (XSLFTableRow row : table.getRows()) {
      List<String> cells = new ArrayList<>();
      for (XSLFTableCell cell : row.getCells()) {
        cells.add(cell.getText());
      }
      rows.add(cells);
    }
    return TableText.rowsOfNonBlankCells(rows);
  }

  private static void appendParagraph(StringBuilder body, String text) {
    if (body.length() > 0) {
      body.append("\n\n");
    }
    body.append(text);
  }

  private static XSLFShape titleShape(XSLFSlide slide) {
    XSLFShape placeholder = slide.getPlaceholder(Placeholder.TITLE);
    return placeholder != null ? placeholder : slide.getPlaceholder(Placeholder.CENTERED_TITLE);
  }

  /** {@code null} if {@code titleShape} is absent, not a text shape, or blank. */
  private static String titleText(XSLFShape titleShape) {
    if (!(titleShape instanceof XSLFTextShape textShape)) {
      return null;
    }
    String text = textShape.getText();
    return text == null || text.isBlank() ? null : text.strip();
  }

  /**
   * Non-content placeholders (slide number, date, footer/header) inherited from the notes master
   * are excluded - they are layout scaffolding, never meaningful body text.
   */
  private static final Set<Placeholder> NON_CONTENT_NOTES_PLACEHOLDERS =
      Set.of(
          Placeholder.SLIDE_NUMBER, Placeholder.DATETIME, Placeholder.FOOTER, Placeholder.HEADER);

  private static String notesText(XSLFSlide slide) {
    XSLFNotes notes = slide.getNotes();
    if (notes == null) {
      return null;
    }
    StringBuilder text = new StringBuilder();
    for (XSLFShape shape : notes.getShapes()) {
      if (!(shape instanceof XSLFTextShape textShape)) {
        continue;
      }
      // getTextType() is null for an ordinary text box that is not inherited from a notes-master
      // placeholder - Set.of(...)#contains(null) throws rather than returning false, so the null
      // case must short-circuit before it.
      Placeholder textType = textShape.getTextType();
      if (textType != null && NON_CONTENT_NOTES_PLACEHOLDERS.contains(textType)) {
        continue;
      }
      String shapeText = textShape.getText();
      if (shapeText != null && !shapeText.isBlank()) {
        if (text.length() > 0) {
          text.append('\n');
        }
        text.append(shapeText.strip());
      }
    }
    return text.length() == 0 ? null : text.toString();
  }
}
