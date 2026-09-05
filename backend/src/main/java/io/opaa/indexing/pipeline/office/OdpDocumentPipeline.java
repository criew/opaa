package io.opaa.indexing.pipeline.office;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.DeduplicatedLines;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.DocumentProperties;
import io.opaa.indexing.pipeline.FileDocumentPipeline;
import io.opaa.indexing.pipeline.HeadingSectionSplitter;
import io.opaa.indexing.pipeline.RepeatingHeaderChunk;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The ODP pipeline (ingestion-pipelines.md, Teil 3, Punkt 2: eine Folie = ein Chunk) - the ODP
 * counterpart of {@link PptxDocumentPipeline}, reading {@code content.xml} through the hardened SAX
 * parser {@link OdfContentXml}, since POI never reads OpenDocument. Every {@code draw:page} with
 * text becomes one chunk, a {@code presentation:class} of {@code "title"} its leading line and
 * {@link ChunkingService#LOCATION_METADATA_KEY location}, notes a final labeled paragraph.
 *
 * <p>{@code styles.xml}'s master page text becomes one deduplicated leading chunk (see {@link
 * RepeatingHeaderChunk}); a malformed one forfeits only that chunk. A presentation whose slides
 * carry no text at all stays {@code NO_EXTRACTABLE_TEXT} regardless of it - template boilerplate
 * must not defeat the scan guard.
 */
public class OdpDocumentPipeline extends FileDocumentPipeline<OdpDocumentPipeline.OdpContent> {

  private static final Logger log = LoggerFactory.getLogger(OdpDocumentPipeline.class);

  static final String ID = "odp";
  static final short VERSION = 2;

  private static final String MASTER_SLIDE_LOCATION = "Masterfolie";

  private static final Set<String> NON_CONTENT_PLACEHOLDER_CLASSES =
      Set.of("header", "footer", "date-time", "page-number");

  private final OdfProperties odfProperties;

  public OdpDocumentPipeline(OdfProperties odfProperties) {
    this.odfProperties = odfProperties;
  }

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
    return Set.of(".odp");
  }

  /**
   * One presentation's reading: one chunk per slide, whether any of them carried text at all, and
   * {@code meta.xml}'s data.
   */
  public record OdpContent(
      List<Document> slideChunks, boolean anySlideHasText, DocumentProperties meta) {}

  @Override
  protected OdpContent read(DocumentPipelineSource source) throws IOException {
    OdpContentHandler handler =
        new OdpContentHandler(
            odfProperties.maxOdpSlides(),
            odfProperties.maxSpaceRepeat(),
            odfProperties.maxTextCharacters());
    if (!OdfContentXml.parse(source.file(), odfProperties.maxContentXmlBytes(), handler)) {
      // Not a genuine ODF ZIP (no content.xml entry at all) - the same "could not be parsed" case
      // a corrupt .odp reaches, distinct from a well-formed but empty presentation.
      throw new IOException("No content.xml entry in ODP file " + source.fileName());
    }
    return new OdpContent(
        handler.chunks(), handler.anySlideHasText(), OdfMetaProperties.read(source, odfProperties));
  }

  @Override
  protected DocumentPipelineResult chunks(DocumentPipelineSource source, OdpContent content) {
    if (content.slideChunks().isEmpty() || !content.anySlideHasText()) {
      // Covers both a genuinely empty <office:presentation/> (zero draw:page elements) and a
      // presentation whose slides carry no text - the same NO_EXTRACTABLE_TEXT outcome
      // TikaFallbackPipeline reported for either case before this pipeline existed. A
      // master-slide chunk never overrides this - see this class's own Javadoc.
      return DocumentPipelineResult.noExtractableText();
    }
    List<Document> chunks = new ArrayList<>(content.slideChunks());
    Document masterSlideChunk =
        RepeatingHeaderChunk.ofOrNull(MASTER_SLIDE_LOCATION, readMasterSlideText(source));
    if (masterSlideChunk != null) {
      chunks.add(0, masterSlideChunk);
    }
    return DocumentPipelineResult.chunked(chunks);
  }

  /** {@code meta.xml}'s title/dates (ADR-0024); ODP slides carry no heading hierarchy to read. */
  @Override
  protected DocumentProperties properties(OdpContent content) {
    return content.meta();
  }

  /**
   * Reads {@code meta.xml} alone: an ODP declares nothing about itself in {@code content.xml}, so
   * the Bestandslauf neither builds a chunk per slide nor loses the title and dates of a
   * presentation whose {@code content.xml} is unreadable.
   */
  @Override
  protected DocumentProperties declaredProperties(DocumentPipelineSource source) {
    return OdfMetaProperties.read(source, odfProperties);
  }

  /**
   * A missing entry, a missing master page or a parse failure all resolve to no master-slide text -
   * this is supplementary content, and a broken {@code styles.xml} must not fail a presentation
   * whose {@code content.xml} parsed successfully.
   */
  private String readMasterSlideText(DocumentPipelineSource source) {
    OdpStylesHandler stylesHandler =
        new OdpStylesHandler(odfProperties.maxSpaceRepeat(), odfProperties.maxTextCharacters());
    try {
      OdfContentXml.parse(
          source.file(), "styles.xml", odfProperties.maxContentXmlBytes(), stylesHandler);
    } catch (IOException | RuntimeException e) {
      log.warn(
          "Could not read styles.xml of ODP document {}; continuing without master-slide text",
          source.fileName(),
          e);
      return "";
    }
    return stylesHandler.masterSlideText();
  }

  /**
   * Collects {@code office:presentation}'s {@code draw:page} children into one chunk per slide,
   * deliberately narrow: it reads only what a slide chunk needs (title, body, notes, slide number)
   * and ignores everything else in {@code content.xml} (styles, images, animations). Paragraph text
   * comes from {@link OdfParagraphTextCollector}, table structure from {@link OdfTableStack}.
   */
  static final class OdpContentHandler extends DefaultHandler {

    private final int maxSlides;
    private final OdfParagraphTextCollector collector;
    private final OdfTableStack tables = new OdfTableStack();
    private int slideCount;

    private final List<Document> chunks = new ArrayList<>();
    private boolean anySlideHasText;

    private boolean insideNotes;
    private String currentFrameClass;

    private boolean hasSlide;
    private int slideNumber;
    private StringBuilder currentTitle;
    private StringBuilder currentBody;
    private StringBuilder currentNotes;
    private boolean currentHasBodyText;

    OdpContentHandler(int maxSlides, int maxSpaceRepeat, long maxTextCharacters) {
      this.maxSlides = maxSlides;
      this.collector =
          OdfParagraphTextCollector.forContent(
              "ODP presentation", maxSpaceRepeat, maxTextCharacters);
    }

    List<Document> chunks() {
      return chunks;
    }

    boolean anySlideHasText() {
      return anySlideHasText;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes)
        throws SAXException {
      switch (qName) {
        case "draw:page" -> {
          if (!insideNotes) {
            flushCurrentSlide();
            slideNumber++;
            slideCount++;
            if (slideCount > maxSlides) {
              throw new SAXException(
                  "ODP presentation exceeds the configured slide limit of " + maxSlides);
            }
            currentTitle = new StringBuilder();
            currentBody = new StringBuilder();
            currentNotes = new StringBuilder();
            currentHasBodyText = false;
            hasSlide = true;
          }
        }
        case "presentation:notes" -> insideNotes = true;
        case "draw:frame" -> currentFrameClass = attributes.getValue("presentation:class");
        default -> {
          // Every other element is either a table (below) or carries no structure this pipeline
          // renders.
        }
      }
      if (!tables.startElement(qName)) {
        collector.startElement(qName, attributes);
      }
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
      collector.characters(ch, start, length);
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
      switch (qName) {
        case "presentation:notes" -> insideNotes = false;
        case "draw:frame" -> currentFrameClass = null;
        default -> {
          // See startElement.
        }
      }
      String tableText = tables.endElement(qName);
      if (tableText != null) {
        if (hasSlide) {
          routeParagraphText(tableText);
        }
        return;
      }
      String value = collector.endElement(qName);
      if (value == null) {
        return;
      }
      if (tables.insideTable()) {
        tables.appendParagraphText(value);
      } else if (hasSlide) {
        routeParagraphText(value);
      }
    }

    @Override
    public void endDocument() {
      flushCurrentSlide();
    }

    private void routeParagraphText(String value) {
      if (value.isBlank()) {
        return;
      }
      String stripped = value.strip();
      if (insideNotes) {
        if (currentFrameClass != null
            && NON_CONTENT_PLACEHOLDER_CLASSES.contains(currentFrameClass)) {
          return;
        }
        if (currentNotes.length() > 0) {
          currentNotes.append('\n');
        }
        currentNotes.append(stripped);
      } else if ("title".equals(currentFrameClass)) {
        if (currentTitle.length() > 0) {
          currentTitle.append('\n');
        }
        currentTitle.append(stripped);
      } else {
        if (currentBody.length() > 0) {
          currentBody.append("\n\n");
        }
        currentBody.append(stripped);
        currentHasBodyText = true;
      }
    }

    private void flushCurrentSlide() {
      if (!hasSlide) {
        return;
      }
      String title = currentTitle.length() == 0 ? null : currentTitle.toString();
      String body = currentBody.toString();
      String notes = currentNotes.length() == 0 ? null : currentNotes.toString();
      String location = "Folie " + slideNumber + (title == null ? "" : ": " + title);
      String text = title == null ? body : body.isEmpty() ? title : title + "\n\n" + body;
      if (notes != null) {
        text = text.isEmpty() ? "Notizen: " + notes : text + "\n\nNotizen: " + notes;
      }
      boolean hasText = title != null || currentHasBodyText || notes != null;
      if (text.isBlank()) {
        text = location;
      }
      Map<String, Object> metadata = new HashMap<>();
      metadata.put(ChunkingService.LOCATION_METADATA_KEY, location);
      chunks.add(new Document(HeadingSectionSplitter.capChunkLength(text), metadata));
      anySlideHasText |= hasText;
      hasSlide = false;
    }
  }

  /**
   * Collects deduplicated paragraph text found inside {@code styles.xml}'s {@code
   * style:master-page} element(s) via {@link OdfParagraphTextCollector}, deliberately narrow like
   * {@link OdpContentHandler}: it does not distinguish title/body/notes the way a slide does. A
   * frame whose {@code presentation:class} is one of {@link #NON_CONTENT_PLACEHOLDER_CLASSES}
   * (layout scaffolding such as an outline "edit master text" prompt) is excluded, mirroring {@link
   * OdpContentHandler}'s own notes filter.
   */
  static final class OdpStylesHandler extends DefaultHandler {

    private final OdfParagraphTextCollector collector;
    private boolean insideMasterPage;
    private String currentFrameClass;
    private final DeduplicatedLines lines = new DeduplicatedLines();

    OdpStylesHandler(int maxSpaceRepeat, long maxTextCharacters) {
      collector = OdfParagraphTextCollector.forStyles(maxSpaceRepeat, maxTextCharacters);
    }

    String masterSlideText() {
      return lines.text();
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes)
        throws SAXException {
      switch (qName) {
        case "style:master-page" -> insideMasterPage = true;
        case "draw:frame" -> currentFrameClass = attributes.getValue("presentation:class");
        default -> {
          // See OdfParagraphTextCollector for text/field handling.
        }
      }
      collector.startElement(qName, attributes);
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
      collector.characters(ch, start, length);
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
      String paragraphText = collector.endElement(qName);
      boolean isPlaceholder =
          currentFrameClass != null && NON_CONTENT_PLACEHOLDER_CLASSES.contains(currentFrameClass);
      if (paragraphText != null && insideMasterPage && !isPlaceholder) {
        lines.add(paragraphText);
      }
      switch (qName) {
        case "style:master-page" -> insideMasterPage = false;
        case "draw:frame" -> currentFrameClass = null;
        default -> {
          // See startElement.
        }
      }
    }
  }
}
