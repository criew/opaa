package io.opaa.indexing.pipeline.office;

import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.HeadingSectionSplitter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import org.springframework.ai.document.Document;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The ODT pipeline (docs/features/ingestion-pipelines.md, Teil 3 Punkt 2) - the ODT counterpart of
 * {@link DocxDocumentPipeline}, reading {@code content.xml} directly through a hardened SAX parser
 * ({@link OdfContentXml}), since Apache POI never reads OpenDocument. Heading level comes from
 * {@code text:h}'s own {@code text:outline-level} (default 1); cutting stops at level 3 ({@link
 * #MAX_CUTTING_LEVEL}), mirroring {@link DocxDocumentPipeline}. A {@code table:table} is read cell
 * by cell into one paragraph-level text block; a table nested inside a cell keeps the outer table's
 * rows intact, but the nested table's own content is discarded entirely - it never reaches the
 * carrier cell either, an accepted narrow gap (docs/features/ingestion-pipelines.md). {@code
 * text:tracked-changes} (deleted text pending review) is skipped entirely. Header/footer text in
 * {@code styles.xml} is not read at all - a known, deliberate content regression versus the
 * previous Tika-based extraction (see docs/features/ingestion-pipelines.md).
 */
public class OdtDocumentPipeline implements DocumentPipeline {

  static final String ID = "odt";
  static final short VERSION = 1;

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
      return DocumentPipelineResult.noContent();
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
        return DocumentPipelineResult.noContent();
      }
      events = handler.events();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read ODT document " + source.fileName(), e);
    }
    List<Document> chunks = HeadingSectionSplitter.chunk(events, MAX_CUTTING_LEVEL);
    if (chunks.isEmpty()) {
      // Covers both a genuinely empty <office:text/> and text that chunked down to nothing - the
      // same NO_EXTRACTABLE_TEXT outcome TikaFallbackPipeline reported for either case before this
      // pipeline existed (#1057), so an already-empty document's user-facing treatment (skipped,
      // not failed) does not change with the routing.
      return DocumentPipelineResult.noExtractableText();
    }
    return DocumentPipelineResult.chunked(chunks);
  }

  /**
   * Collects {@code office:text}'s {@code text:h}/{@code text:p}/{@code table:table} children into
   * a flat {@link HeadingSectionSplitter.Event} stream, deliberately narrow: it reads only what
   * that splitter needs and ignores everything else in {@code content.xml} (styles, images,
   * change-tracking).
   */
  private static final class OdtContentHandler extends DefaultHandler {

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
}
