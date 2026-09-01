package io.opaa.indexing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * The PPTX pipeline (docs/features/ingestion-pipelines.md, Teil 2: "PPTX | Eine Folie = ein
 * Chunk"). Reads directly through Apache POI's {@code XMLSlideShow} (already a project dependency
 * for {@link TabularDocumentPipeline}'s XLSX reader) - no framework returns a per-slide document,
 * and Tika's own extraction flattens every slide into one text block (ingestion-pipelines.md, Teil
 * 0: "Ein Chunk enthält dann das Ende von Folie 4 und den Anfang von Folie 5").
 *
 * <p><b>Every slide becomes exactly one chunk</b>, even a slide with no text at all (an image-only
 * slide) - it still gets a chunk carrying only its {@link #location} line, the same treatment
 * {@link HtmlDocumentPipeline} gives a heading with no body text, so the slide numbering a citation
 * relies on stays contiguous.
 *
 * <p>The slide's title placeholder (if any) becomes the chunk's leading line and the {@link
 * ChunkingService#LOCATION_METADATA_KEY location}; every other text shape's text follows, in shape
 * order; the slide's own speaker notes, if any, are appended as a final, clearly labeled paragraph
 * - context for retrieval, not something a citation would quote as slide content.
 */
public class PptxDocumentPipeline implements DocumentPipeline {

  private static final Logger log = LoggerFactory.getLogger(PptxDocumentPipeline.class);

  static final String ID = "pptx";
  static final short VERSION = 1;

  /**
   * Last-resort backstop for a pathologically large slide - mirrors {@link
   * HtmlDocumentPipeline#HARD_CHUNK_CHAR_LIMIT}. A slide has no ordinary size-control mechanism of
   * its own (one slide is always one chunk, per the Teil 2 table), so this is the only limit.
   */
  static final int HARD_CHUNK_CHAR_LIMIT = 20_000;

  private static final String TRUNCATION_MARKER = " […gekürzt]";

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
    List<XSLFSlide> slides = readSlides(source);
    if (slides == null) {
      return DocumentPipelineResult.noContent();
    }
    if (slides.isEmpty()) {
      return DocumentPipelineResult.noContent();
    }
    List<Document> chunks = new ArrayList<>();
    for (int i = 0; i < slides.size(); i++) {
      chunks.add(buildChunk(slides.get(i), i + 1));
    }
    return DocumentPipelineResult.chunked(chunks);
  }

  private static List<XSLFSlide> readSlides(DocumentPipelineSource source) {
    try (InputStream in = openStream(source)) {
      try (XMLSlideShow slideShow = new XMLSlideShow(in)) {
        return slideShow.getSlides();
      }
    } catch (IOException | RuntimeException e) {
      log.warn("Could not read PPTX document {}", source.fileName(), e);
      return null;
    }
  }

  private static InputStream openStream(DocumentPipelineSource source) throws IOException {
    if (source.file() != null) {
      return Files.newInputStream(source.file());
    }
    return new ByteArrayInputStream(source.extractedText().getBytes(StandardCharsets.UTF_8));
  }

  private static Document buildChunk(XSLFSlide slide, int slideNumber) {
    String title = slideTitle(slide);
    StringBuilder body = new StringBuilder();
    for (XSLFShape shape : slide.getShapes()) {
      if (!(shape instanceof XSLFTextShape textShape) || isTitleShape(shape, title)) {
        continue;
      }
      String text = textShape.getText();
      if (text != null && !text.isBlank()) {
        appendParagraph(body, text.strip());
      }
    }
    String notes = notesText(slide);
    if (notes != null) {
      appendParagraph(body, "Notizen: " + notes);
    }
    String location = "Folie " + slideNumber + (title == null ? "" : ": " + title);
    String text =
        title == null ? body.toString() : body.length() == 0 ? title : title + "\n\n" + body;
    if (text.isBlank()) {
      text = location;
    }
    Map<String, Object> metadata = new HashMap<>();
    metadata.put(ChunkingService.LOCATION_METADATA_KEY, location);
    return new Document(capChunkLength(text), metadata);
  }

  private static void appendParagraph(StringBuilder body, String text) {
    if (body.length() > 0) {
      body.append("\n\n");
    }
    body.append(text);
  }

  /** The title placeholder's text, or {@code null} if the slide has none / it is blank. */
  private static String slideTitle(XSLFSlide slide) {
    var placeholder = slide.getPlaceholder(org.apache.poi.sl.usermodel.Placeholder.TITLE);
    if (placeholder == null) {
      placeholder = slide.getPlaceholder(org.apache.poi.sl.usermodel.Placeholder.CENTERED_TITLE);
    }
    if (!(placeholder instanceof XSLFTextShape titleShape)) {
      return null;
    }
    String text = titleShape.getText();
    return text == null || text.isBlank() ? null : text.strip();
  }

  private static boolean isTitleShape(XSLFShape shape, String title) {
    return title != null
        && shape instanceof XSLFTextShape textShape
        && title.equals(textShape.getText() == null ? null : textShape.getText().strip());
  }

  private static String notesText(XSLFSlide slide) {
    XSLFNotes notes = slide.getNotes();
    if (notes == null) {
      return null;
    }
    StringBuilder text = new StringBuilder();
    for (XSLFShape shape : notes.getShapes()) {
      if (shape instanceof XSLFTextShape textShape) {
        String shapeText = textShape.getText();
        if (shapeText != null && !shapeText.isBlank()) {
          if (text.length() > 0) {
            text.append("\n");
          }
          text.append(shapeText.strip());
        }
      }
    }
    return text.length() == 0 ? null : text.toString();
  }

  private static String capChunkLength(String text) {
    if (text.length() <= HARD_CHUNK_CHAR_LIMIT) {
      return text;
    }
    log.warn(
        "A chunk exceeds the hard limit of {} characters ({} actual); truncating",
        HARD_CHUNK_CHAR_LIMIT,
        text.length());
    return text.substring(0, HARD_CHUNK_CHAR_LIMIT - TRUNCATION_MARKER.length())
        + TRUNCATION_MARKER;
  }
}
