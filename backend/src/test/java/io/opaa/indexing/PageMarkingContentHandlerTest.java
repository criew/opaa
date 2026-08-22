package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.xml.sax.helpers.AttributesImpl;

/**
 * #667: Tika's {@code <div class="page">} boundaries survive as form feeds in the extracted text.
 */
class PageMarkingContentHandlerTest {

  private static final String XHTML = "http://www.w3.org/1999/xhtml";

  @Test
  void writesAFormFeedBeforeEveryPageAfterTheFirst() throws Exception {
    var handler = new PageMarkingContentHandler();
    handler.startDocument();
    handler.startElement(XHTML, "html", "html", new AttributesImpl());
    handler.startElement(XHTML, "body", "body", new AttributesImpl());
    page(handler, "Erste Seite");
    page(handler, "Zweite Seite");
    page(handler, "Dritte Seite");
    handler.endElement(XHTML, "body", "body");
    handler.endElement(XHTML, "html", "html");
    handler.endDocument();

    String text = handler.toString();

    assertThat(text.chars().filter(c -> c == ChunkLocationResolver.PAGE_BREAK).count())
        .isEqualTo(2);
    assertThat(text.indexOf("Erste Seite"))
        .isLessThan(text.indexOf(ChunkLocationResolver.PAGE_BREAK));
    assertThat(text).contains("Zweite Seite").contains("Dritte Seite");
  }

  @Test
  void leavesOtherDivsAlone() throws Exception {
    var handler = new PageMarkingContentHandler();
    handler.startDocument();
    handler.startElement(XHTML, "html", "html", new AttributesImpl());
    handler.startElement(XHTML, "body", "body", new AttributesImpl());
    for (String cssClass : List.of("section", "section")) {
      var attrs = new AttributesImpl();
      attrs.addAttribute("", "class", "class", "CDATA", cssClass);
      handler.startElement(XHTML, "div", "div", attrs);
      handler.characters("Ohne Seite".toCharArray(), 0, 10);
      handler.endElement(XHTML, "div", "div");
    }
    handler.endElement(XHTML, "body", "body");
    handler.endElement(XHTML, "html", "html");
    handler.endDocument();

    assertThat(handler.toString())
        .contains("Ohne Seite")
        .doesNotContain(String.valueOf(ChunkLocationResolver.PAGE_BREAK));
  }

  private static void page(PageMarkingContentHandler handler, String content) throws Exception {
    var attrs = new AttributesImpl();
    attrs.addAttribute("", "class", "class", "CDATA", "page");
    handler.startElement(XHTML, "div", "div", attrs);
    handler.characters(content.toCharArray(), 0, content.length());
    handler.endElement(XHTML, "div", "div");
  }
}
