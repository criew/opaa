package io.opaa.indexing.pipeline.office;

import io.opaa.indexing.pipeline.DeduplicatedLines;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.DocumentProperties;
import io.opaa.indexing.pipeline.DocumentTitleLine;
import io.opaa.indexing.pipeline.FileDocumentPipeline;
import io.opaa.indexing.pipeline.HeadingSectionSplitter;
import io.opaa.indexing.pipeline.RepeatingHeaderChunk;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The ODT pipeline (ingestion-pipelines.md, Teil 3, Punkt 2) - the ODT counterpart of {@link
 * DocxDocumentPipeline}, reading {@code content.xml} through the hardened SAX parser {@link
 * OdfContentXml}, since POI never reads OpenDocument. Heading level comes from {@code text:h}'s
 * {@code text:outline-level}; cutting stops at {@link #MAX_CUTTING_LEVEL}. A table becomes one text
 * block, a table nested in a cell is discarded, {@code text:tracked-changes} is skipped.
 *
 * <p>{@code styles.xml}'s header/footer text, every variant of it, becomes one deduplicated leading
 * chunk (see {@link RepeatingHeaderChunk}); a malformed {@code styles.xml} forfeits only that
 * chunk. It never rescues a body-less document from {@code NO_EXTRACTABLE_TEXT}: template text is
 * no evidence of content, and a scanned letter must stay visible as OCR-needing.
 */
public class OdtDocumentPipeline extends FileDocumentPipeline<OdtDocumentPipeline.OdtContent> {

  private static final Logger log = LoggerFactory.getLogger(OdtDocumentPipeline.class);

  static final String ID = "odt";
  static final short VERSION = 2;

  private static final String HEADER_FOOTER_LOCATION = "Kopf-/Fußzeile";

  /** Cutting stops at level 3, mirroring {@link DocxDocumentPipeline#MAX_CUTTING_LEVEL}. */
  private static final int MAX_CUTTING_LEVEL = 3;

  private final OdfProperties odfProperties;

  public OdtDocumentPipeline(OdfProperties odfProperties) {
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
    return Set.of(".odt");
  }

  /** One document's reading: {@code content.xml}'s event stream and {@code meta.xml}'s data. */
  public record OdtContent(List<HeadingSectionSplitter.Event> events, DocumentProperties meta) {}

  @Override
  protected OdtContent read(DocumentPipelineSource source) throws IOException {
    OdtContentHandler handler =
        new OdtContentHandler(
            odfProperties.maxOdtParagraphs(),
            odfProperties.maxSpaceRepeat(),
            odfProperties.maxTextCharacters());
    if (!OdfContentXml.parse(source.file(), odfProperties.maxContentXmlBytes(), handler)) {
      // Not a genuine ODF ZIP (no content.xml entry at all) - the same "could not be parsed" case
      // a corrupt .odt reaches, distinct from a well-formed but empty document.
      throw new IOException("No content.xml entry in ODT file " + source.fileName());
    }
    return new OdtContent(handler.events(), OdfMetaProperties.read(source, odfProperties));
  }

  @Override
  protected DocumentPipelineResult chunks(DocumentPipelineSource source, OdtContent content) {
    List<Document> chunks = HeadingSectionSplitter.chunk(content.events(), MAX_CUTTING_LEVEL);
    if (chunks.isEmpty()) {
      // Covers both a genuinely empty <office:text/> and text that chunked down to nothing - the
      // same NO_EXTRACTABLE_TEXT outcome TikaFallbackPipeline reported for either case before this
      // pipeline existed, so an already-empty document's user-facing treatment (skipped,
      // not failed) does not change with the routing. Header/footer text never rescues this
      // outcome - see this class's own Javadoc on why the guard ignores it entirely.
      return DocumentPipelineResult.noExtractableText();
    }
    List<Document> allChunks = new ArrayList<>(chunks);
    Document headerFooterChunk =
        RepeatingHeaderChunk.ofOrNull(HEADER_FOOTER_LOCATION, readHeaderFooterText(source));
    if (headerFooterChunk != null) {
      allChunks.add(0, headerFooterChunk);
    }
    return DocumentPipelineResult.chunked(allChunks);
  }

  /**
   * {@code meta.xml}'s title/dates, the first level-1 {@code text:h} (ADR-0024) and the opening of
   * the body text.
   */
  @Override
  protected DocumentProperties properties(OdtContent content) {
    return content
        .meta()
        .withFirstHeading(HeadingSectionSplitter.firstTopLevelHeading(content.events()))
        .withTitleLine(DocumentTitleLine.ofEvents(content.events()));
  }

  /**
   * A missing entry, a missing header/footer or a parse failure all resolve to no header/footer
   * text - this is supplementary content, and a broken {@code styles.xml} must not fail a document
   * whose {@code content.xml} parsed successfully.
   */
  private String readHeaderFooterText(DocumentPipelineSource source) {
    OdtStylesHandler stylesHandler =
        new OdtStylesHandler(odfProperties.maxSpaceRepeat(), odfProperties.maxTextCharacters());
    try {
      OdfContentXml.parse(
          source.file(), "styles.xml", odfProperties.maxContentXmlBytes(), stylesHandler);
    } catch (IOException | RuntimeException e) {
      log.warn(
          "Could not read styles.xml of ODT document {}; continuing without header/footer text",
          source.fileName(),
          e);
      return "";
    }
    return stylesHandler.headerFooterText();
  }

  /**
   * Collects {@code office:text}'s {@code text:h}/{@code text:p}/{@code table:table} children into
   * a flat {@link HeadingSectionSplitter.Event} stream, deliberately narrow: it reads only what
   * that splitter needs and ignores everything else in {@code content.xml} (styles, images,
   * change-tracking). Paragraph text comes from {@link OdfParagraphTextCollector}, table structure
   * from {@link OdfTableStack}.
   */
  static final class OdtContentHandler extends DefaultHandler {

    private final int maxParagraphs;
    private final OdfParagraphTextCollector collector;
    private final OdfTableStack tables = new OdfTableStack();
    private int paragraphCount;

    private final List<HeadingSectionSplitter.Event> events = new ArrayList<>();

    private Integer headingLevel;
    private boolean insideTrackedChanges;

    OdtContentHandler(int maxParagraphs, int maxSpaceRepeat, long maxTextCharacters) {
      this.maxParagraphs = maxParagraphs;
      this.collector =
          OdfParagraphTextCollector.forContent("ODT document", maxSpaceRepeat, maxTextCharacters);
    }

    List<HeadingSectionSplitter.Event> events() {
      return events;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes)
        throws SAXException {
      if (insideTrackedChanges) {
        return;
      }
      if ("text:tracked-changes".equals(qName)) {
        insideTrackedChanges = true;
        return;
      }
      if ("text:h".equals(qName) && !collector.insideParagraph()) {
        headingLevel =
            OdfParagraphTextCollector.parsePositiveIntOrDefault(
                attributes.getValue("text:outline-level"), 1);
      } else if ("text:p".equals(qName) && !collector.insideParagraph()) {
        headingLevel = null;
      }
      if (tables.startElement(qName)) {
        return;
      }
      collector.startElement(qName, attributes);
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
      if (!insideTrackedChanges) {
        collector.characters(ch, start, length);
      }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
      if (insideTrackedChanges) {
        if ("text:tracked-changes".equals(qName)) {
          insideTrackedChanges = false;
        }
        return;
      }
      String tableText = tables.endElement(qName);
      if (tableText != null) {
        incrementParagraphCount();
        events.add(new HeadingSectionSplitter.Paragraph(tableText));
        return;
      }
      String value = collector.endElement(qName);
      if (value == null) {
        return;
      }
      if (tables.insideTable()) {
        tables.appendParagraphText(value);
      } else {
        recordParagraphOrHeading(qName, value);
      }
    }

    private void recordParagraphOrHeading(String qName, String value) throws SAXException {
      if ("text:h".equals(qName)) {
        incrementParagraphCount();
        events.add(new HeadingSectionSplitter.Heading(headingLevel, value));
      } else if (!value.isBlank()) {
        incrementParagraphCount();
        events.add(new HeadingSectionSplitter.Paragraph(value));
      }
    }

    private void incrementParagraphCount() throws SAXException {
      paragraphCount++;
      if (paragraphCount > maxParagraphs) {
        throw new SAXException(
            "ODT document exceeds the configured paragraph limit of " + maxParagraphs);
      }
    }
  }

  /**
   * Collects deduplicated {@code style:header}/{@code style:footer} paragraph text from {@code
   * styles.xml}'s master page(s) via {@link OdfParagraphTextCollector}. Every variant ({@code
   * style:header}, {@code style:header-left}, {@code style:header-first} and their footer
   * counterparts) is read - a document with "different first page" set carries its letterhead only
   * in the first-page variant, which is exactly the case this class exists for. The header role and
   * the footer role each stay a single {@link DeduplicatedLines deduplicated} block.
   */
  static final class OdtStylesHandler extends DefaultHandler {

    private final OdfParagraphTextCollector collector;
    private boolean insideHeader;
    private boolean insideFooter;
    private final DeduplicatedLines headerLines = new DeduplicatedLines();
    private final DeduplicatedLines footerLines = new DeduplicatedLines();

    OdtStylesHandler(int maxSpaceRepeat, long maxTextCharacters) {
      collector = OdfParagraphTextCollector.forStyles(maxSpaceRepeat, maxTextCharacters);
    }

    /** Header text followed by footer text, blank-line separated when both are present. */
    String headerFooterText() {
      if (headerLines.isEmpty()) {
        return footerLines.text();
      }
      if (footerLines.isEmpty()) {
        return headerLines.text();
      }
      return headerLines.text() + "\n\n" + footerLines.text();
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes)
        throws SAXException {
      switch (qName) {
        case "style:header", "style:header-left", "style:header-first" -> insideHeader = true;
        case "style:footer", "style:footer-left", "style:footer-first" -> insideFooter = true;
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
      if (paragraphText != null) {
        if (insideHeader) {
          headerLines.add(paragraphText);
        } else if (insideFooter) {
          footerLines.add(paragraphText);
        }
      }
      switch (qName) {
        case "style:header", "style:header-left", "style:header-first" -> insideHeader = false;
        case "style:footer", "style:footer-left", "style:footer-first" -> insideFooter = false;
        default -> {
          // See startElement.
        }
      }
    }
  }
}
