package io.opaa.indexing.pipeline.office;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.HeadingSectionSplitter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.document.Document;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The ODP pipeline (docs/features/ingestion-pipelines.md, Teil 3 Punkt 2: eine Folie = ein Chunk) -
 * the ODP counterpart of {@link PptxDocumentPipeline}, but reading {@code content.xml} directly
 * through a hardened SAX parser ({@link OdfContentXml}) rather than Apache POI, which reads OOXML
 * and the legacy binary Office formats but never OpenDocument (see {@link OdtDocumentPipeline}'s
 * own Javadoc for the full reasoning, shared verbatim here).
 *
 * <p>Every {@code draw:page} with any text becomes exactly one chunk, mirroring {@link
 * PptxDocumentPipeline}. A frame's role comes from its own {@code presentation:class} attribute:
 * {@code "title"} becomes the chunk's leading line and its {@link
 * ChunkingService#LOCATION_METADATA_KEY location}; every other frame's text (including {@code
 * "subtitle"}, matching {@link PptxDocumentPipeline}'s own narrower title concept) becomes body
 * text, joined with a blank line between frames - unlike {@link PptxDocumentPipeline}, this reader
 * does not distinguish paragraphs within one frame from paragraphs across frames, a deliberate,
 * narrow simplification since the SAX event stream carries no per-frame grouping of its own. Text
 * inside {@code presentation:notes} becomes a final labeled paragraph, with the same non-content
 * placeholder classes ({@code header}/{@code footer}/{@code date-time}/{@code page-number})
 * excluded that {@link PptxDocumentPipeline} excludes from its own notes reading. A presentation
 * where no slide carries any text at all is rejected as {@code NO_EXTRACTABLE_TEXT}.
 */
public class OdpDocumentPipeline implements DocumentPipeline {

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
    OdpContentHandler handler = new OdpContentHandler(odfProperties.maxOdpSlides());
    boolean found;
    try {
      found = OdfContentXml.parse(source.file(), odfProperties.maxContentXmlBytes(), handler);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read ODP document " + source.fileName(), e);
    }
    if (!found || handler.chunks().isEmpty()) {
      return DocumentPipelineResult.noContent();
    }
    if (!handler.anySlideHasText()) {
      return DocumentPipelineResult.noExtractableText();
    }
    return DocumentPipelineResult.chunked(handler.chunks());
  }

  /**
   * Collects {@code office:presentation}'s {@code draw:page} children into one chunk per slide,
   * deliberately narrow: it reads only what {@link #run} needs (title, body, notes, slide number)
   * and ignores everything else in {@code content.xml} (styles, images, animations).
   */
  private static final class OdpContentHandler extends DefaultHandler {

    private final int maxSlides;
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

    private int paragraphDepth;
    private final StringBuilder text = new StringBuilder();

    private boolean insideTable;
    private boolean insideCell;
    private List<String> tableRows;
    private List<String> currentRowCells;
    private final StringBuilder cellText = new StringBuilder();

    OdpContentHandler(int maxSlides) {
      this.maxSlides = maxSlides;
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
          insideTable = true;
          tableRows = new ArrayList<>();
        }
        case "table:table-row" -> {
          if (insideTable) {
            currentRowCells = new ArrayList<>();
          }
        }
        case "table:table-cell", "table:covered-table-cell" -> {
          if (insideTable) {
            insideCell = true;
            cellText.setLength(0);
          }
        }
        default -> {
          // Every other element (styles, images, animations) carries no structure this pipeline
          // renders and is ignored.
        }
      }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
      if (paragraphDepth > 0) {
        text.append(ch, start, length);
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
            if (insideTable) {
              if (insideCell) {
                if (cellText.length() > 0) {
                  cellText.append(' ');
                }
                cellText.append(value.strip());
              }
            } else if (hasSlide) {
              routeParagraphText(value);
            }
          }
        }
        case "table:table-cell", "table:covered-table-cell" -> {
          if (insideTable) {
            insideCell = false;
            if (currentRowCells != null) {
              currentRowCells.add(cellText.toString());
            }
          }
        }
        case "table:table-row" -> {
          if (insideTable && currentRowCells != null) {
            String rowText = String.join(" | ", currentRowCells);
            if (!rowText.isBlank()) {
              tableRows.add(rowText);
            }
            currentRowCells = null;
          }
        }
        case "table:table" -> {
          if (insideTable) {
            insideTable = false;
            if (tableRows != null && hasSlide) {
              String tableText = String.join("\n", tableRows);
              if (!tableText.isBlank()) {
                routeParagraphText(tableText);
              }
            }
            tableRows = null;
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
  }
}
