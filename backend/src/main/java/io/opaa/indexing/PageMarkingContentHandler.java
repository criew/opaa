package io.opaa.indexing;

import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

/**
 * Tika body handler that keeps page boundaries in the extracted text (#667). Tika's paginating
 * parsers (PDF, Office) wrap every page in {@code <div class="page">}; plain text extraction
 * flattens that to a newline, after which no chunk can say which page it came from. This handler
 * writes a form feed ({@link ChunkLocationResolver#PAGE_BREAK}) at the start of every page after
 * the first, which {@link ChunkLocationResolver} turns into "S. n" locations and {@link
 * ChunkingService} strips again before the chunk text is embedded - the marker never reaches the
 * vector store.
 */
final class PageMarkingContentHandler extends BodyContentHandler {

  private static final char[] PAGE_BREAK = {ChunkLocationResolver.PAGE_BREAK};

  private int pagesStarted;

  PageMarkingContentHandler() {
    super(-1);
  }

  @Override
  public void startElement(String uri, String localName, String name, Attributes atts)
      throws SAXException {
    if ("div".equals(localName) && "page".equals(atts.getValue("class"))) {
      if (pagesStarted > 0) {
        characters(PAGE_BREAK, 0, 1);
      }
      pagesStarted++;
    }
    super.startElement(uri, localName, name, atts);
  }
}
