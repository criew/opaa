package io.opaa.format.file.html;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

/**
 * The one boilerplate/main-content selection the HTML pipeline and the feed connector's detail page
 * extraction share: unconditional chrome goes everywhere, header/footer only outside the chosen
 * roots, every top-level match is a root, and the main-content selector is a parameter.
 */
class HtmlContentRootsTest {

  private static final int MANY_SIBLINGS = 600_000;

  private static final String PAGE =
      """
      <html><body>
        <div id="cookie-banner"><p>Cookies</p></div>
        <header><a href="/">Startseite</a></header>
        <nav><a href="/kontakt">Kontakt</a></nav>
        <main>
          <nav>Unternavigation</nav>
          <aside>Randspalte</aside>
          <header><h1>Titel</h1></header>
          <article><p>Inhalt.</p></article>
          <footer><p>Autor</p></footer>
        </main>
        <footer><p>Impressum</p></footer>
      </body></html>
      """;

  @Test
  void selectsEveryTopLevelMatchAndStripsChromeOutsideItAndUnconditionalChromeInsideIt() {
    Document document = Jsoup.parse(PAGE);

    List<Element> roots = HtmlContentRoots.select(document);

    assertThat(roots).hasSize(1);
    assertThat(roots.getFirst().tagName()).isEqualTo("main");
    String text = roots.getFirst().text();
    assertThat(text)
        .contains("Titel")
        .contains("Inhalt.")
        .contains("Autor")
        .doesNotContain("Unternavigation")
        .doesNotContain("Randspalte");
    assertThat(document.body().text())
        .doesNotContain("Cookies")
        .doesNotContain("Startseite")
        .doesNotContain("Kontakt")
        .doesNotContain("Impressum");
  }

  @Test
  void withoutAMatchTheBodyIsTheRootAndHeaderAndFooterGoToo() {
    Document document =
        Jsoup.parse(
            "<html><body><header>Kopf</header><div>Eigentlicher Inhalt</div>"
                + "<footer>Fuss</footer></body></html>");

    List<Element> roots = HtmlContentRoots.select(document);

    assertThat(roots).hasSize(1);
    assertThat(roots.getFirst().tagName()).isEqualTo("body");
    assertThat(roots.getFirst().text()).isEqualTo("Eigentlicher Inhalt");
  }

  @Test
  void severalArticlesAreSeveralRootsButANestedMatchIsNotItsOwn() {
    Document document =
        Jsoup.parse(
            "<html><body><main><article><p>Eins</p></article></main>"
                + "<article><p>Zwei</p></article></body></html>");

    List<Element> roots = HtmlContentRoots.select(document);

    assertThat(roots).extracting(Element::text).containsExactly("Eins", "Zwei");
  }

  @Test
  void aConfiguredSelectorReplacesTheDefaultOne() {
    Document document =
        Jsoup.parse(
            "<html><body><main><p>Standardbereich</p></main>"
                + "<div id=\"content\"><p>Konfigurierter Bereich</p></div></body></html>");

    List<Element> roots = HtmlContentRoots.select(document, "#content");

    assertThat(roots).extracting(Element::text).containsExactly("Konfigurierter Bereich");
  }

  @Test
  void manyContentRootsAreSelectedInLinearTime() {
    // regression guard: membership in the root set must not be a list scan per ancestor
    Document document =
        Jsoup.parse(
            "<html><body>"
                + "<article><header>Kopf</header><p>Text</p></article>".repeat(150_000)
                + "<footer>Fuß</footer></body></html>");

    List<Element> roots =
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> HtmlContentRoots.select(document));

    assertThat(roots).hasSize(150_000);
    assertThat(document.select("footer")).isEmpty();
    assertThat(document.select("header")).hasSize(150_000);
  }

  @Test
  void manyUnconditionalBoilerplateSiblingsAreRemovedInLinearTime() {
    // regression guard for #3387: removing one sibling at a time shifts all later siblings
    Document document =
        Jsoup.parse(
            "<html><body>" + "<nav>Menü</nav>".repeat(MANY_SIBLINGS) + "<p>Text</p></body></html>");

    List<Element> roots = selectWithinLinearBound(document);

    assertThat(roots).extracting(Element::text).containsExactly("Text");
    assertThat(document.select("nav")).isEmpty();
  }

  @Test
  void manyFooterSiblingsUnderABodyWithoutMainContentAreRemovedInLinearTime() {
    // regression guard for #3387: removing one sibling at a time shifts all later siblings
    Document document =
        Jsoup.parse(
            "<html><body>"
                + "<footer>Fuß</footer>".repeat(MANY_SIBLINGS)
                + "<p>Text</p></body></html>");

    List<Element> roots = selectWithinLinearBound(document);

    assertThat(roots).extracting(Element::text).containsExactly("Text");
    assertThat(document.select("footer")).isEmpty();
  }

  @Test
  void manyFooterSiblingsOutsideTheMainContentAreRemovedInLinearTime() {
    // regression guard for #3387: removing one sibling at a time shifts all later siblings
    Document document =
        Jsoup.parse(
            "<html><body><main><p>Text</p><footer>Autor</footer></main>"
                + "<footer>Fuß</footer>".repeat(MANY_SIBLINGS)
                + "</body></html>");

    List<Element> roots = selectWithinLinearBound(document);

    assertThat(roots).extracting(Element::text).containsExactly("Text Autor");
    assertThat(document.select("footer")).hasSize(1);
  }

  @Test
  void boilerplateIsRemovedWhereverItStandsAndTheRemainingOrderIsKept() {
    Document document =
        Jsoup.parse(
            "<html><body><p>Eins</p><nav>Menü</nav>Zwei<footer>Fuß</footer><div><p>Drei</p>"
                + "<script>x()</script><footer>Innen</footer><p>Vier</p></div>"
                + "<aside><nav>Unten</nav><p>Rand</p></aside><p>Fünf</p></body></html>");

    List<Element> roots = HtmlContentRoots.select(document);

    assertThat(roots).hasSize(1);
    assertThat(roots.getFirst().html())
        .isEqualTo("<p>Eins</p>\nZwei\n<div>\n <p>Drei</p>\n <p>Vier</p>\n</div>\n<p>Fünf</p>");
  }

  /**
   * Removing {@link #MANY_SIBLINGS} siblings in one pass per parent takes well under a second;
   * removing them one by one takes over ten, so the bound tells the two apart on a slow runner.
   */
  private static List<Element> selectWithinLinearBound(Document document) {
    return assertTimeoutPreemptively(
        Duration.ofSeconds(5), () -> HtmlContentRoots.select(document));
  }
}
