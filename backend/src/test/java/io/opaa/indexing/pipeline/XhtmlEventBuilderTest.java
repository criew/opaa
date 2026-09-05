package io.opaa.indexing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.pipeline.HeadingSectionSplitter.Event;
import io.opaa.indexing.pipeline.HeadingSectionSplitter.Heading;
import io.opaa.indexing.pipeline.HeadingSectionSplitter.Paragraph;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.Test;

/**
 * The one DOM-to-event walk the HTML and the Confluence pipeline share: headings, block
 * boundaries, inline whitespace, tables, lists, verbatim blocks - and the element rule a format
 * hooks its own elements into. Exercised on both parsers, since Confluence pages are XML-parsed.
 */
class XhtmlEventBuilderTest {

  private static List<Event> events(String html) {
    return new XhtmlEventBuilder().build(Jsoup.parse(html).body());
  }

  private static List<Event> xmlEvents(String xml, XhtmlEventBuilder.ElementRule rule) {
    return new XhtmlEventBuilder(rule).build(Jsoup.parse(xml, "", Parser.xmlParser()));
  }

  @Test
  void headingsOfEveryLevelBecomeHeadingEventsWithTheirLevel() {
    assertThat(events("<h1>Eins</h1><p>a</p><h3>Drei</h3><h6>Sechs</h6><p>b</p>"))
        .containsExactly(
            new Heading(1, "Eins"),
            new Paragraph("a"),
            new Heading(3, "Drei"),
            new Heading(6, "Sechs"),
            new Paragraph("b"));
  }

  @Test
  void nestedBlocksYieldOneParagraphPerBlockInDocumentOrder() {
    assertThat(
            events(
                "<section><h2>Titel</h2><div><p>Erster</p><blockquote>Zitat</blockquote></div>"
                    + "<p>Zweiter<br>Dritter</p></section>"))
        .containsExactly(
            new Heading(2, "Titel"),
            new Paragraph("Erster"),
            new Paragraph("Zitat"),
            new Paragraph("Zweiter"),
            new Paragraph("Dritter"));
  }

  @Test
  void textDirectlyAfterABlockStartsItsOwnParagraph() {
    assertThat(events("<p>Absatz</p>Nachsatz<p>Weiter</p>"))
        .containsExactly(
            new Paragraph("Absatz"), new Paragraph("Nachsatz"), new Paragraph("Weiter"));
  }

  @Test
  void inlineMarkupJoinsWordsExactlyAsTheSourceSpacesThem() {
    // "Personalausweis" must not become "Personal ausweis", and a real space must not be lost.
    assertThat(events("<p><b>Personal</b>ausweis  beantragen</p><p><b>Personal</b> ausweis</p>"))
        .containsExactly(
            new Paragraph("Personalausweis beantragen"), new Paragraph("Personal ausweis"));
  }

  @Test
  void nonBreakingSpacesCollapseLikeWhitespaceAndLeaveNoEmptyBlock() {
    assertThat(events("<p>Frist:&nbsp;14 Tage</p><p>&nbsp;</p><p> </p><p>Ende.</p>"))
        .containsExactly(new Paragraph("Frist: 14 Tage"), new Paragraph("Ende."));
  }

  @Test
  void aHeadingTitleIsReadThroughTheSameWalkAsBodyText() {
    assertThat(events("<h2>  Antrag <em>stellen</em>\n und <b>Ab</b>gabe </h2>"))
        .containsExactly(new Heading(2, "Antrag stellen und Abgabe"));
  }

  @Test
  void tablesBecomeOneLinePerRowWithCellsSeparated() {
    assertThat(
            events(
                "<table><thead><tr><th>Vorgang</th><th>Frist</th></tr></thead><tbody>"
                    + "<tr><td>Bauantrag</td><td>14 Tage</td></tr>"
                    + "<tr><td>Anzeige</td><td><ul><li>sofort</li><li>formlos</li></ul></td></tr>"
                    + "</tbody></table>"))
        .containsExactly(
            new Paragraph("Vorgang | Frist"),
            new Paragraph("Bauantrag | 14 Tage"),
            new Paragraph("Anzeige | • sofort • formlos"));
  }

  @Test
  void aNestedTableIsFlattenedIntoItsOuterCellOnce() {
    assertThat(
            events(
                "<table><tr><td>Aussen</td><td><table><tr><td>Innen1</td><td>Innen2</td></tr>"
                    + "</table></td></tr></table>"))
        .containsExactly(new Paragraph("Aussen | Innen1 | Innen2"));
  }

  @Test
  void listsBecomeOneLinePerItemWithNestingCarriedByTheMarker() {
    assertThat(
            events(
                "<ul><li>Lageplan</li><li>Bauzeichnungen<ul><li>Grundriss<ul><li>EG</li></ul></li>"
                    + "</ul></li></ul><ol><li>Eins</li><li>Zwei<ol><li>Zwei-a</li></ol></li></ol>"))
        .containsExactly(
            new Paragraph("• Lageplan"),
            new Paragraph("• Bauzeichnungen"),
            new Paragraph("◦ Grundriss"),
            new Paragraph("▪ EG"),
            new Paragraph("1. Eins"),
            new Paragraph("2. Zwei"),
            new Paragraph("2.1. Zwei-a"));
  }

  @Test
  void preformattedTextKeepsItsLineBreaks() {
    assertThat(events("<pre>  zeile 1\n    zeile 2  </pre><p>danach</p>"))
        .containsExactly(new Paragraph("zeile 1\n    zeile 2"), new Paragraph("danach"));
  }

  @Test
  void scriptsAndStylesAreInvisible() {
    assertThat(events("<p>Text</p><script>var x = 1;</script><style>p {}</style>"))
        .containsExactly(new Paragraph("Text"));
  }

  @Test
  void anElementRuleIsConsultedBeforeTheBuiltInWalkAndCanUseTheBuilder() {
    XhtmlEventBuilder.ElementRule rule =
        (element, builder) ->
            switch (element.tagName()) {
              case "x:hidden" -> true;
              case "x:box" -> {
                builder.flushBlock();
                builder.emitLine("[" + element.attr("title") + "]");
                builder.walkChildren(element);
                builder.flushBlock();
                yield true;
              }
              case "x:code" -> {
                builder.verbatim(element.wholeText());
                yield true;
              }
              case "x:tag" -> {
                builder.appendInline(" " + element.attr("name") + " ");
                yield true;
              }
              default -> false;
            };

    assertThat(
            xmlEvents(
                "<h1>Antrag <x:tag name=\"Entwurf\"/></h1><x:hidden>nie</x:hidden>"
                    + "<x:box title=\"Hinweis\"><p>Im Kasten</p></x:box>"
                    + "<x:code>a\n b</x:code><p>Status <x:tag name=\"offen\"/>.</p>",
                rule))
        .containsExactly(
            new Heading(1, "Antrag Entwurf"),
            new Paragraph("[Hinweis]"),
            new Paragraph("Im Kasten"),
            new Paragraph("a\n b"),
            new Paragraph("Status offen ."));
  }

  @Test
  void inlineTextFlattensAnElementIncludingItsBlocksToOneLine() {
    Element cell =
        Jsoup.parseBodyFragment(
                "<div><p>Erster</p><ul><li>a</li><li>b</li></ul><p>Letzter</p></div>")
            .selectFirst("div");

    assertThat(new XhtmlEventBuilder().inlineText(cell)).isEqualTo("Erster • a • b Letzter");
  }

  @Test
  void anEmptyDocumentYieldsNoEvents() {
    assertThat(events("<div>  </div><p>&nbsp;</p>")).isEmpty();
  }
}
