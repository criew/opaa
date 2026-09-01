package io.opaa.indexing.pipeline.office;

import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.HeadingSectionSplitter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.ai.document.Document;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The ODT pipeline (docs/features/ingestion-pipelines.md, Teil 3 Punkt 2) - the ODT counterpart of
 * {@link DocxDocumentPipeline}, but reading {@code content.xml} directly through a hardened SAX
 * parser ({@link OdfContentXml}) rather than Apache POI, which reads OOXML and the legacy binary
 * Office formats but never OpenDocument (see that pipeline's own Javadoc for the full reasoning,
 * shared verbatim here).
 *
 * <p>Heading level comes straight from a {@code text:h} element's own {@code text:outline-level}
 * attribute (defaulting to 1 when absent, per the ODF schema) - no style-name pattern matching is
 * needed the way {@link DocxDocumentPipeline} needs one for Word's own built-in styles. Cutting
 * stops at level 3 ({@link #MAX_CUTTING_LEVEL}), mirroring {@link DocxDocumentPipeline}. A {@code
 * table:table} is read cell by cell into one paragraph-level text block per table (never a
 * heading), mirroring {@link DocxDocumentPipeline#run}'s own table handling; a paragraph nested
 * inside another (e.g. a footnote body) folds into its enclosing paragraph's text rather than being
 * split out on its own, and a table nested inside a table cell is not specially handled - both are
 * accepted, narrow gaps for structures this document type rarely carries.
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
      OdtContentHandler handler = new OdtContentHandler(odfProperties.maxOdtParagraphs());
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
    private int paragraphCount;

    private final List<HeadingSectionSplitter.Event> events = new ArrayList<>();

    private int paragraphDepth;
    private Integer headingLevel;
    private final StringBuilder text = new StringBuilder();

    private boolean insideTable;
    private boolean insideCell;
    private List<String> tableRows;
    private List<String> currentRowCells;
    private final StringBuilder cellText = new StringBuilder();

    OdtContentHandler(int maxParagraphs) {
      this.maxParagraphs = maxParagraphs;
    }

    List<HeadingSectionSplitter.Event> events() {
      return events;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
      switch (qName) {
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
          // Every other element (styles, images, change-tracking) carries no structure this
          // pipeline renders and is ignored.
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
    public void endElement(String uri, String localName, String qName) throws SAXException {
      switch (qName) {
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
            } else {
              recordParagraphOrHeading(qName, value);
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
            if (tableRows != null) {
              String tableText = String.join("\n", tableRows);
              if (!tableText.isBlank()) {
                incrementParagraphCount();
                events.add(new HeadingSectionSplitter.Paragraph(tableText));
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
  }
}
