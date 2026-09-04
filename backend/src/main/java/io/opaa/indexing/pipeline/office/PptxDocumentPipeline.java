package io.opaa.indexing.pipeline.office;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.DocumentProperties;
import io.opaa.indexing.pipeline.HeadingSectionSplitter;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * The PPTX pipeline (docs/features/ingestion-pipelines.md, Teil 2: eine Folie = ein Chunk). Reads
 * directly through Apache POI's {@link XMLSlideShow}, since no framework returns a per-slide
 * document and Tika flattens every slide into one text block.
 *
 * <p>Every slide with any text becomes exactly one chunk, including a heading-only slide, so the
 * slide numbering a citation relies on stays contiguous. A presentation where no slide carries any
 * text at all is rejected as {@code NO_EXTRACTABLE_TEXT} rather than silently indexed as N
 * content-free chunks. The title placeholder (if any) becomes the chunk's leading line and its
 * {@link ChunkingService#LOCATION_METADATA_KEY location}; every other shape's text follows in shape
 * order, descending into a {@link XSLFGroupShape} recursively and reading a {@link XSLFTable} row
 * by row. Speaker notes, if any, are appended as a final labeled paragraph.
 */
public class PptxDocumentPipeline implements DocumentPipeline {

  private static final Logger log = LoggerFactory.getLogger(PptxDocumentPipeline.class);

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

  @Override
  public DocumentPipelineResult run(DocumentPipelineSource source) {
    if (source.file() == null) {
      return DocumentPipelineResult.parseFailed();
    }
    PptxContent content = readContent(source);
    if (content == null) {
      // The file could not be opened as a PPTX at all - see readContent.
      return DocumentPipelineResult.parseFailed();
    }
    if (content.slides().isEmpty()) {
      return DocumentPipelineResult.noContent();
    }
    List<XSLFSlide> slides = content.slides();
    List<Document> chunks = new ArrayList<>();
    boolean anySlideHasText = false;
    for (int i = 0; i < slides.size(); i++) {
      SlideChunk built = buildChunk(slides.get(i), i + 1);
      chunks.add(built.document());
      anySlideHasText |= built.hasText();
    }
    if (!anySlideHasText) {
      return DocumentPipelineResult.noExtractableText();
    }
    return DocumentPipelineResult.chunked(chunks).withProperties(content.properties());
  }

  /**
   * The OOXML core properties (dc:title, created, modified) plus the first slide's title as the
   * first heading (ADR-0024) - one parse, the same read {@link #run} takes.
   */
  @Override
  public DocumentProperties readProperties(DocumentPipelineSource source) {
    if (source.file() == null) {
      return DocumentProperties.EMPTY;
    }
    PptxContent content = readContent(source);
    return content == null ? DocumentProperties.EMPTY : content.properties();
  }

  private record PptxContent(List<XSLFSlide> slides, DocumentProperties properties) {}

  /** {@code null} when the file could not be opened as a PPTX at all - reported as no content. */
  private static PptxContent readContent(DocumentPipelineSource source) {
    try (InputStream in = Files.newInputStream(source.file())) {
      try (XMLSlideShow slideShow = new XMLSlideShow(in)) {
        List<XSLFSlide> slides = slideShow.getSlides();
        POIXMLProperties.CoreProperties core = slideShow.getProperties().getCoreProperties();
        String firstHeading = slides.isEmpty() ? null : titleText(titleShape(slides.get(0)));
        return new PptxContent(
            slides,
            new DocumentProperties(
                core.getTitle(),
                DocumentProperties.toLocalDate(core.getCreated()),
                DocumentProperties.toLocalDate(core.getModified()),
                null,
                firstHeading,
                Map.of()));
      }
    } catch (IOException | RuntimeException e) {
      log.warn("Could not read PPTX document {}", source.fileName(), e);
      return null;
    }
  }

  /**
   * @param hasText whether the slide carried any real text - see this class's own Javadoc.
   */
  private record SlideChunk(Document document, boolean hasText) {}

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

  /**
   * One line per row, cells joined by {@code " | "} - mirrors {@link DocxDocumentPipeline}'s table
   * reading.
   */
  private static String tableText(XSLFTable table) {
    StringBuilder text = new StringBuilder();
    for (XSLFTableRow row : table.getRows()) {
      StringBuilder rowText = new StringBuilder();
      for (XSLFTableCell cell : row.getCells()) {
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
   * are excluded - they are layout scaffolding, never meaningful body text (#1104 review, Nit 7).
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
      // case must short-circuit before it (#1104 review round 2, wichtig 2).
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
