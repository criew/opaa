package io.opaa.indexing.pipeline.office;

import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.DocumentProperties;
import io.opaa.indexing.pipeline.DocumentTitleLine;
import io.opaa.indexing.pipeline.HeadingSectionSplitter;
import io.opaa.indexing.pipeline.RepeatingHeaderChunk;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
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
public class OdtDocumentPipeline implements DocumentPipeline {

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

  @Override
  public DocumentPipelineResult run(DocumentPipelineSource source) {
    if (source.file() == null) {
      // An ODT pipeline is only ever reached through a genuine .odt file (never RSS-extracted
      // text, ADR-0017 decision 2) - defensive fallback, mirrors DocxDocumentPipeline/
      // PptxDocumentPipeline.
      return DocumentPipelineResult.parseFailed();
    }
    List<HeadingSectionSplitter.Event> events;
    try {
      OdtContentHandler handler =
          new OdtContentHandler(
              odfProperties.maxOdtParagraphs(),
              odfProperties.maxSpaceRepeat(),
              odfProperties.maxTextCharacters());
      boolean found =
          OdfContentXml.parse(source.file(), odfProperties.maxContentXmlBytes(), handler);
      if (!found) {
        // Not a genuine ODF ZIP (no content.xml entry at all) - the same "could not be parsed"
        // case DocxDocumentPipeline reports for a corrupt .docx, distinct from a well-formed but
        // empty document below.
        return DocumentPipelineResult.parseFailed();
      }
      events = handler.events();
    } catch (IOException | RuntimeException e) {
      // Unparsable content (a corrupt ZIP, a rejected XXE attempt, a limit SAXException wraps into
      // an IOException) is reported the same way as PDF/DOCX/PPTX/Tabular - see
      // DocumentPipelineResult's own Javadoc for the shared contract.
      log.warn("Could not read ODT document {}", source.fileName(), e);
      return DocumentPipelineResult.parseFailed();
    }
    List<Document> chunks = HeadingSectionSplitter.chunk(events, MAX_CUTTING_LEVEL);
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
    return DocumentPipelineResult.chunked(allChunks)
        .withProperties(
            OdfMetaProperties.read(source, odfProperties)
                .withFirstHeading(firstTopLevelHeading(events))
                .withTitleLine(DocumentTitleLine.ofEvents(events)));
  }

  /**
   * {@code meta.xml}'s title/dates, the first level-1 {@code text:h} (ADR-0024) and the opening of
   * the body text.
   */
  @Override
  public DocumentProperties readProperties(DocumentPipelineSource source) {
    if (source.file() == null) {
      return DocumentProperties.EMPTY;
    }
    DocumentProperties meta = OdfMetaProperties.read(source, odfProperties);
    try {
      OdtContentHandler handler =
          new OdtContentHandler(
              odfProperties.maxOdtParagraphs(),
              odfProperties.maxSpaceRepeat(),
              odfProperties.maxTextCharacters());
      if (OdfContentXml.parse(source.file(), odfProperties.maxContentXmlBytes(), handler)) {
        return meta.withFirstHeading(firstTopLevelHeading(handler.events()))
            .withTitleLine(DocumentTitleLine.ofEvents(handler.events()));
      }
    } catch (IOException | RuntimeException e) {
      log.warn("Could not read headings of ODT document {}", source.fileName(), e);
    }
    return meta;
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

  /**
   * A missing entry, a missing header/footer or a parse failure all resolve to no header/footer
   * text - this is supplementary content, and a broken {@code styles.xml} must not fail a document
   * whose {@code content.xml} parsed successfully above.
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
   * change-tracking).
   */
  static final class OdtContentHandler extends DefaultHandler {

    private final int maxParagraphs;
    private final int maxSpaceRepeat;
    private final long maxTextCharacters;
    private int paragraphCount;
    // Cumulative across the whole document, not reset with text - text.setLength(0) only bounds
    // one paragraph's buffer, not how many text:s elements a single paragraph can carry (see
    // OdfProperties#maxTextCharacters).
    private long textCharacterCount;

    private final List<HeadingSectionSplitter.Event> events = new ArrayList<>();

    private int paragraphDepth;
    private Integer headingLevel;
    private final StringBuilder text = new StringBuilder();

    private int tableDepth;
    // One frame per currently open table:table, deepest on top; a nested table gets its own row
    // list and cell buffer so it cannot overwrite the carrier row/cell of the table around it.
    private final Deque<TableFrame> tableStack = new ArrayDeque<>();

    private boolean insideTrackedChanges;

    OdtContentHandler(int maxParagraphs, int maxSpaceRepeat, long maxTextCharacters) {
      this.maxParagraphs = maxParagraphs;
      this.maxSpaceRepeat = maxSpaceRepeat;
      this.maxTextCharacters = maxTextCharacters;
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
      switch (qName) {
        case "text:tracked-changes" -> insideTrackedChanges = true;
        case "text:h" -> {
          if (paragraphDepth == 0) {
            text.setLength(0);
            headingLevel = parsePositiveIntOrDefault(attributes.getValue("text:outline-level"), 1);
          }
          paragraphDepth++;
        }
        case "text:p" -> {
          if (paragraphDepth == 0) {
            text.setLength(0);
            headingLevel = null;
          }
          paragraphDepth++;
        }
        case "table:table" -> {
          tableDepth++;
          tableStack.push(new TableFrame());
        }
        case "table:table-row" -> {
          if (tableDepth > 0) {
            tableStack.peek().currentRowCells = new ArrayList<>();
          }
        }
        case "table:table-cell", "table:covered-table-cell" -> {
          if (tableDepth > 0) {
            TableFrame frame = tableStack.peek();
            frame.insideCell = true;
            frame.cellText.setLength(0);
          }
        }
        case "text:s" -> appendRepeatedSpace(attributes);
        case "text:tab" -> {
          if (paragraphDepth > 0) {
            text.append('\t');
          }
        }
        case "text:line-break" -> {
          if (paragraphDepth > 0) {
            text.append('\n');
          }
        }
        default -> {
          // Every other element (styles, images, change-tracking) carries no structure this
          // pipeline renders and is ignored.
        }
      }
    }

    private void appendRepeatedSpace(Attributes attributes) throws SAXException {
      if (paragraphDepth == 0) {
        return;
      }
      int count = parsePositiveIntOrDefault(attributes.getValue("text:c"), 1);
      int repeated = Math.min(count, maxSpaceRepeat);
      checkTextCharacterBudget(repeated);
      text.append(" ".repeat(repeated));
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
      if (paragraphDepth > 0) {
        checkTextCharacterBudget(length);
        text.append(ch, start, length);
      }
    }

    private void checkTextCharacterBudget(int added) throws SAXException {
      textCharacterCount += added;
      if (textCharacterCount > maxTextCharacters) {
        throw new SAXException(
            "ODT document exceeds the configured text character limit of " + maxTextCharacters);
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
      switch (qName) {
        case "text:h", "text:p" -> {
          paragraphDepth--;
          if (paragraphDepth == 0) {
            String value = text.toString();
            if (tableDepth > 0) {
              TableFrame frame = tableStack.peek();
              if (frame.insideCell) {
                if (frame.cellText.length() > 0) {
                  frame.cellText.append(' ');
                }
                frame.cellText.append(value.strip());
              }
            } else {
              recordParagraphOrHeading(qName, value);
            }
          }
        }
        case "table:table-cell", "table:covered-table-cell" -> {
          if (tableDepth > 0) {
            TableFrame frame = tableStack.peek();
            frame.insideCell = false;
            if (frame.currentRowCells != null) {
              frame.currentRowCells.add(frame.cellText.toString());
            }
          }
        }
        case "table:table-row" -> {
          if (tableDepth > 0) {
            TableFrame frame = tableStack.peek();
            if (frame.currentRowCells != null) {
              String rowText = String.join(" | ", frame.currentRowCells);
              if (!rowText.isBlank()) {
                frame.rows.add(rowText);
              }
              frame.currentRowCells = null;
            }
          }
        }
        case "table:table" -> {
          tableDepth--;
          TableFrame frame = tableStack.pop();
          if (tableDepth == 0) {
            // A nested table's own frame (tableDepth > 0 here) is discarded without emitting -
            // its rows do not separately become an event, only the carrier row's cell survives.
            String tableText = String.join("\n", frame.rows);
            if (!tableText.isBlank()) {
              incrementParagraphCount();
              events.add(new HeadingSectionSplitter.Paragraph(tableText));
            }
          }
        }
        default -> {
          // See startElement.
        }
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

    private static Integer parsePositiveIntOrDefault(String value, int defaultValue) {
      if (value == null) {
        return defaultValue;
      }
      try {
        int parsed = Integer.parseInt(value);
        return parsed > 0 ? parsed : defaultValue;
      } catch (NumberFormatException e) {
        return defaultValue;
      }
    }

    /** Per-table-nesting-level row/cell accumulation state, see {@link #tableStack}. */
    private static final class TableFrame {
      private final List<String> rows = new ArrayList<>();
      private final StringBuilder cellText = new StringBuilder();
      private List<String> currentRowCells;
      private boolean insideCell;
    }
  }

  /**
   * Collects deduplicated {@code style:header}/{@code style:footer} paragraph text from {@code
   * styles.xml}'s master page(s) via {@link OdfParagraphTextCollector}. Every variant ({@code
   * style:header}, {@code style:header-left}, {@code style:header-first} and their footer
   * counterparts) is read - a document with "different first page" set carries its letterhead only
   * in the first-page variant, which is exactly the case this class exists for. Two paragraphs
   * whose whitespace-normalized text is equal (the common case of the same header/footer repeated
   * verbatim across variants or master pages) contribute only once, keeping the header role and the
   * footer role each a single deduplicated block.
   */
  static final class OdtStylesHandler extends DefaultHandler {

    private final OdfParagraphTextCollector collector;
    private boolean insideHeader;
    private boolean insideFooter;
    // Normalized (whitespace-collapsed) line -> first-seen original line, insertion-ordered so the
    // rendered text preserves the order paragraphs appeared in.
    private final Map<String, String> headerLines = new LinkedHashMap<>();
    private final Map<String, String> footerLines = new LinkedHashMap<>();

    OdtStylesHandler(int maxSpaceRepeat, long maxTextCharacters) {
      collector = new OdfParagraphTextCollector(maxSpaceRepeat, maxTextCharacters);
    }

    /** Header text followed by footer text, blank-line separated when both are present. */
    String headerFooterText() {
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
          addLineIfNew(headerLines, paragraphText);
        } else if (insideFooter) {
          addLineIfNew(footerLines, paragraphText);
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

    private static void addLineIfNew(Map<String, String> lines, String value) {
      String stripped = value.strip();
      if (stripped.isBlank()) {
        return;
      }
      // \s alone does not match a non-breaking space (U+00A0) or narrow no-break space
      // (U+202F) - both routine in an authority letterhead's column separators - so a variant
      // using one and the default using a plain space would otherwise be treated as distinct
      // lines and both survive deduplication.
      lines.putIfAbsent(stripped.replaceAll("[\\s\\u00A0\\u202F]+", " "), stripped);
    }
  }
}
