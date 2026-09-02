package io.opaa.indexing.pipeline.office;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.HeadingSectionSplitter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
 * The ODP pipeline (docs/features/ingestion-pipelines.md, Teil 3 Punkt 2: eine Folie = ein Chunk) -
 * the ODP counterpart of {@link PptxDocumentPipeline}, reading {@code content.xml} directly through
 * a hardened SAX parser ({@link OdfContentXml}), since Apache POI never reads OpenDocument. Every
 * {@code draw:page} with any text becomes exactly one chunk; a frame's {@code presentation:class}
 * of {@code "title"} becomes the chunk's leading line and {@link
 * ChunkingService#LOCATION_METADATA_KEY location}, every other frame's text becomes body text.
 * {@code presentation:notes} becomes a final labeled paragraph, excluding the same non-content
 * placeholder classes {@link PptxDocumentPipeline} excludes. A presentation where no slide carries
 * any text is rejected as {@code NO_EXTRACTABLE_TEXT}. A placeholder class on a slide's own {@code
 * draw:frame} (not just in notes) and master-slide text in {@code styles.xml} are not read at all,
 * a known, deliberate content regression versus the previous Tika-based extraction (see
 * docs/features/ingestion-pipelines.md).
 */
public class OdpDocumentPipeline implements DocumentPipeline {

  private static final Logger log = LoggerFactory.getLogger(OdpDocumentPipeline.class);

  static final String ID = "odp";
  static final short VERSION = 1;

  private static final Set<String> NON_CONTENT_NOTES_CLASSES =
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

  @Override
  public DocumentPipelineResult run(DocumentPipelineSource source) {
    if (source.file() == null) {
      return DocumentPipelineResult.noContent();
    }
    OdpContentHandler handler =
        new OdpContentHandler(
            odfProperties.maxOdpSlides(),
            odfProperties.maxSpaceRepeat(),
            odfProperties.maxTextCharacters());
    boolean found;
    try {
      found = OdfContentXml.parse(source.file(), odfProperties.maxContentXmlBytes(), handler);
    } catch (IOException | RuntimeException e) {
      // Unparsable content (a corrupt ZIP, a rejected XXE attempt, a limit SAXException wraps into
      // an IOException) is reported the same way as PDF/DOCX/PPTX/Tabular/ODT - see
      // DocumentPipelineResult's own Javadoc for the shared contract.
      log.warn("Could not read ODP document {}", source.fileName(), e);
      return DocumentPipelineResult.noContent();
    }
    if (!found) {
      // Not a genuine ODF ZIP (no content.xml entry at all) - the same "could not be parsed" case
      // OdtDocumentPipeline reports for a corrupt .odt, distinct from a well-formed but empty
      // presentation below.
      return DocumentPipelineResult.noContent();
    }
    if (handler.chunks().isEmpty() || !handler.anySlideHasText()) {
      // Covers both a genuinely empty <office:presentation/> (zero draw:page elements) and a
      // presentation whose slides carry no text - the same NO_EXTRACTABLE_TEXT outcome
      // TikaFallbackPipeline reported for either case before this pipeline existed (#1057).
      return DocumentPipelineResult.noExtractableText();
    }
    return DocumentPipelineResult.chunked(handler.chunks());
  }

  /**
   * Collects {@code office:presentation}'s {@code draw:page} children into one chunk per slide,
   * deliberately narrow: it reads only what {@link #run} needs (title, body, notes, slide number)
   * and ignores everything else in {@code content.xml} (styles, images, animations).
   */
  static final class OdpContentHandler extends DefaultHandler {

    private final int maxSlides;
    private final int maxSpaceRepeat;
    private final long maxTextCharacters;
    private int slideCount;
    // Cumulative across the whole document, not reset with text - text.setLength(0) only bounds
    // one paragraph's buffer, not how many text:s elements a single paragraph can carry (see
    // OdfProperties#maxTextCharacters).
    private long textCharacterCount;

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

    private int paragraphDepth;
    private final StringBuilder text = new StringBuilder();

    private int tableDepth;
    // One frame per currently open table:table, deepest on top; a nested table gets its own row
    // list and cell buffer so it cannot overwrite the carrier row/cell of the table around it.
    private final Deque<TableFrame> tableStack = new ArrayDeque<>();

    OdpContentHandler(int maxSlides, int maxSpaceRepeat, long maxTextCharacters) {
      this.maxSlides = maxSlides;
      this.maxSpaceRepeat = maxSpaceRepeat;
      this.maxTextCharacters = maxTextCharacters;
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
        case "text:h", "text:p" -> {
          if (paragraphDepth == 0) {
            text.setLength(0);
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
          // Every other element (styles, images, animations) carries no structure this pipeline
          // renders and is ignored.
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

    private static int parsePositiveIntOrDefault(String value, int defaultValue) {
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
            "ODP presentation exceeds the configured text character limit of " + maxTextCharacters);
      }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
      switch (qName) {
        case "presentation:notes" -> insideNotes = false;
        case "draw:frame" -> currentFrameClass = null;
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
            } else if (hasSlide) {
              routeParagraphText(value);
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
          if (tableDepth == 0 && hasSlide) {
            // A nested table's own frame (tableDepth > 0 here) is discarded without emitting -
            // its rows do not separately become body text, only the carrier row's cell survives.
            String tableText = String.join("\n", frame.rows);
            if (!tableText.isBlank()) {
              routeParagraphText(tableText);
            }
          }
        }
        default -> {
          // See startElement.
        }
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
        if (currentFrameClass != null && NON_CONTENT_NOTES_CLASSES.contains(currentFrameClass)) {
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

    /** Per-table-nesting-level row/cell accumulation state, see {@link #tableStack}. */
    private static final class TableFrame {
      private final List<String> rows = new ArrayList<>();
      private final StringBuilder cellText = new StringBuilder();
      private List<String> currentRowCells;
      private boolean insideCell;
    }
  }
}
