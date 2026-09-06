package io.opaa.indexing.pipeline.html;

import static org.assertj.core.api.Assertions.assertThat;

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
}
