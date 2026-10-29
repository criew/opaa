package io.opaa.format.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.opaa.format.shared.HeadingSectionSplitter.Event;
import io.opaa.format.shared.HeadingSectionSplitter.Heading;
import io.opaa.format.shared.HeadingSectionSplitter.Paragraph;
import java.time.Duration;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.Test;

/**
 * The one DOM-to-event walk the HTML and the Confluence pipeline share: headings, block boundaries,
 * inline whitespace, tables, lists, verbatim blocks - and the element rule a format hooks its own
 * elements into. Exercised on both parsers, since Confluence pages are XML-parsed.
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

  // regression guard for #1357: a block child inside an item must not flush the item's text
  // without its marker (Confluence Cloud and CMS output wrap item text in <p>)
  @Test
  void aListItemWhoseTextIsWrappedInABlockKeepsItsMarker() {
    assertThat(events("<ul><li><p>Lageplan</p></li><li><p>Bauzeichnungen</p></li></ul>"))
        .containsExactly(new Paragraph("• Lageplan"), new Paragraph("• Bauzeichnungen"));
  }

  @Test
  void aListItemWithSeveralBlocksMarksTheFirstAndContinuesUnmarked() {
    assertThat(
            events(
                "<ol><li><p>Antrag stellen</p><p>Frist: 14 Tage</p></li>"
                    + "<li><p>Bescheid abwarten</p></li></ol>"))
        .containsExactly(
            new Paragraph("1. Antrag stellen"),
            new Paragraph("Frist: 14 Tage"),
            new Paragraph("2. Bescheid abwarten"));
  }

  @Test
  void aNestedListInsideABlockItemStaysNestedUnderItsMarkedItem() {
    assertThat(
            events(
                "<ul><li><p>Nachweise</p><ul><li>Meldebescheinigung</li><li><p>Lichtbild</p></li>"
                    + "</ul></li><li>Gebühr</li></ul>"
                    + "<ol><li><p>Eins</p><ol><li><p>Eins-a</p></li></ol></li></ol>"))
        .containsExactly(
            new Paragraph("• Nachweise"),
            new Paragraph("◦ Meldebescheinigung"),
            new Paragraph("◦ Lichtbild"),
            new Paragraph("• Gebühr"),
            new Paragraph("1. Eins"),
            new Paragraph("1.1. Eins-a"));
  }

  @Test
  void inlineTextBeforeABlockCarriesTheMarkerAndTheBlockFollowsUnmarked() {
    assertThat(events("<ul><li>Text<p>Mehr</p></li></ul>"))
        .containsExactly(new Paragraph("• Text"), new Paragraph("Mehr"));
  }

  // A line break inside an item ends its first paragraph: the marker leads the first line (before
  // #1357 the first line was unmarked and the marker landed on the last one).
  @Test
  void aLineBreakInsideAnItemLeavesTheMarkerOnTheFirstLine() {
    assertThat(events("<ul><li>Zeile1<br>Zeile2</li><li>Weiter</li></ul>"))
        .containsExactly(
            new Paragraph("• Zeile1"), new Paragraph("Zeile2"), new Paragraph("• Weiter"));
  }

  @Test
  void aLineEmittedByARuleAsTheItemsFirstContentCarriesTheMarker() {
    XhtmlEventBuilder.ElementRule rule =
        (element, builder) -> {
          if (!element.tagName().equals("x:box")) {
            return false;
          }
          builder.flushBlock();
          builder.emitLine("[" + element.attr("title") + "]");
          builder.walkChildren(element);
          builder.flushBlock();
          return true;
        };

    assertThat(
            xmlEvents(
                "<ul><li><x:box title=\"Hinweis\"><p>Im Kasten</p></x:box></li>"
                    + "<li>Zweiter</li></ul>",
                rule))
        .containsExactly(
            new Paragraph("• [Hinweis]"), new Paragraph("Im Kasten"), new Paragraph("• Zweiter"));
  }

  @Test
  void aListInsideAWrapperOfAnItemContinuesAtTheNextDepth() {
    assertThat(
            events(
                "<ul><li>Aussen<div><ul><li>Innen<div><ul><li>Tief</li></ul></div></li></ul></div>"
                    + "</li><li>Danach</li></ul>"
                    + "<ol><li>Eins<div><ol><li>Eins-a</li></ol></div></li><li>Zwei</li></ol>"))
        .containsExactly(
            new Paragraph("• Aussen"),
            new Paragraph("◦ Innen"),
            new Paragraph("▪ Tief"),
            new Paragraph("• Danach"),
            new Paragraph("1. Eins"),
            new Paragraph("1.1. Eins-a"),
            new Paragraph("2. Zwei"));
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

  @Test
  void deeplyNestedWideTablesAreWalkedInLinearTime() {
    // regression guard: a table must not search its whole subtree for its rows, and nested cell
    // text must not be rebuilt once per nesting level
    String rows = "<tr><td>Zeile</td><td>Wert</td></tr>".repeat(1_000);
    String html =
        ("<table>" + rows + "<tr><td>").repeat(120) + "Kern" + "</td></tr></table>".repeat(120);
    Element body = Jsoup.parse(html).body();

    List<Event> events =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10), () -> new XhtmlEventBuilder().build(body));

    assertThat(events.getFirst()).isEqualTo(new Paragraph("Zeile | Wert"));
    assertThat(events).anyMatch(event -> ((Paragraph) event).text().endsWith("Kern"));
  }

  @Test
  void tablesNestedBeyondTheDepthLimitKeepTheirTextWithoutRowLayout() {
    int levels = XhtmlEventBuilder.MAX_TABLE_DEPTH + 1;
    String html =
        "<table><tr><td>A</td><td>B</td></tr><tr><td>".repeat(levels)
            + "Kern"
            + "</td></tr></table>".repeat(levels);

    List<Event> events = events(html);

    // levels 1 to MAX - 1 keep "A | B" rows, the level at MAX reads as plain blocks
    String nested = "A | B ".repeat(XhtmlEventBuilder.MAX_TABLE_DEPTH - 1);
    assertThat(events).containsExactly(new Paragraph("A | B"), new Paragraph(nested + "A B Kern"));
  }
}
