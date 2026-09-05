package io.opaa.indexing.pipeline.html;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.PassthroughMetadataKeysTestSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The HTML pipeline (ingestion-pipelines.md Teil 3, Punkt 4): boilerplate outside the chosen
 * content area never reaches a chunk, the cut follows h1-h3 with every chunk carrying its heading
 * path (in text and metadata alike), tables and lists keep their structure through the shared
 * {@code XhtmlEventBuilder}, and an oversized section is split further at block boundaries rather
 * than growing a single chunk without bound.
 */
class HtmlDocumentPipelineTest {

  @TempDir Path tempDir;

  private final HtmlDocumentPipeline pipeline = new HtmlDocumentPipeline();

  @Test
  void claimsExactlyHtml() {
    assertThat(pipeline.handledFormats()).containsExactly(".html");
    assertThat(pipeline.id()).isEqualTo("html");
    assertThat(pipeline.version()).isEqualTo((short) 2);
  }

  /** ADR-0024: the page title and the first h1 are the HTML format's declared properties. */
  @Test
  void readsTheTitleAndTheFirstH1AsDocumentProperties() {
    DocumentPipelineSource source =
        DocumentPipelineSource.ofExtractedText(REALISTIC_PAGE, "seite.html");

    io.opaa.indexing.pipeline.DocumentProperties properties = pipeline.readProperties(source);

    assertThat(properties.title()).isEqualTo("Buergeramt");
    assertThat(properties.firstHeading()).isNotBlank();
    assertThat(REALISTIC_PAGE).contains("<h1>" + properties.firstHeading() + "</h1>");
    assertThat(pipeline.run(source).properties()).isEqualTo(properties);
    assertThat(
            pipeline
                .readProperties(DocumentPipelineSource.ofExtractedText("<p>nur Text</p>", "x.html"))
                .title())
        .isNull();
  }

  /**
   * the Dokumentart is read from the title line, and an HTML page has no line breaks of its own -
   * the first text block is that line, the label paragraph below it is not part of it.
   */
  @Test
  void readsTheFirstTextBlockAsTheTitleLine() {
    assertThat(
            pipeline
                .readProperties(
                    DocumentPipelineSource.ofExtractedText(REALISTIC_PAGE, "seite.html"))
                .titleLine())
        .isEqualTo("Personalausweis beantragen");

    String withLabelLine =
        """
        <html><body><main>
          <h1>Fabrikneues Fahrzeug anmelden</h1>
          <p><strong>Formular:</strong> RF-KFZ-001</p>
          <p>Die Zulassungsstelle nimmt den Antrag persoenlich entgegen.</p>
        </main></body></html>
        """;

    assertThat(
            pipeline
                .readProperties(DocumentPipelineSource.ofExtractedText(withLabelLine, "kfz.html"))
                .titleLine())
        .isEqualTo("Fabrikneues Fahrzeug anmelden");
  }

  // A realistic Government Site Builder-style page: nav, header, footer and a cookie banner
  // outside <main>, plus nested headings (h1 > h2 > h3) inside it.
  private static final String REALISTIC_PAGE =
      """
      <html>
        <head><title>Buergeramt</title></head>
        <body>
          <div id="cookie-banner">
            <p>Wir verwenden Cookies, um unsere Website zu verbessern.</p>
            <button>Akzeptieren</button>
          </div>
          <header>
            <a href="/">Startseite</a>
          </header>
          <nav>
            <ul>
              <li><a href="/buergeramt">Buergeramt</a></li>
              <li><a href="/kontakt">Kontakt</a></li>
            </ul>
          </nav>
          <main>
            <h1>Personalausweis beantragen</h1>
            <p>Der Personalausweis ist ein amtliches Ausweisdokument.</p>
            <h2>Voraussetzungen</h2>
            <p>Sie muessen persoenlich erscheinen und ein biometrisches Lichtbild mitbringen.</p>
            <h3>Fuer Minderjaehrige</h3>
            <p>Es ist die Zustimmung der Erziehungsberechtigten erforderlich.</p>
            <h2>Gebuehren</h2>
            <p>Die Gebuehr betraegt 37,00 EUR fuer Antragstellende ab 24 Jahren.</p>
          </main>
          <footer>
            <p>Impressum | Datenschutz</p>
          </footer>
        </body>
      </html>
      """;

  @Test
  void boilerplateOutsideTheContentAreaNeverReachesAChunk() throws IOException {
    DocumentPipelineResult result = pipeline.run(sourceFor(REALISTIC_PAGE));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    String allText = String.join("\n", result.chunks().stream().map(d -> d.getText()).toList());
    assertThat(allText)
        .doesNotContain("Cookies")
        .doesNotContain("Akzeptieren")
        .doesNotContain("Startseite")
        .doesNotContain("Kontakt")
        .doesNotContain("Impressum")
        .doesNotContain("Datenschutz");
  }

  @Test
  void cutsFollowH1ToH3WithOneChunkPerSectionAndTheHeadingInTheChunkText() throws IOException {
    DocumentPipelineResult result = pipeline.run(sourceFor(REALISTIC_PAGE));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    // Personalausweis (h1), Voraussetzungen (h2), Fuer Minderjaehrige (h3), Gebuehren (h2).
    assertThat(result.chunks()).hasSize(4);
    // the heading path must be part of the chunk's own text, not only
    // its location metadatum - otherwise it is unreachable for embedding and for the lexical path
    // .
    assertThat(result.chunks().get(0).getText())
        .startsWith("Personalausweis beantragen")
        .contains("amtliches Ausweisdokument");
    assertThat(result.chunks().get(0).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Abschn. Personalausweis beantragen");
    assertThat(result.chunks().get(1).getText())
        .startsWith("Personalausweis beantragen › Voraussetzungen")
        .contains("biometrisches Lichtbild");
    assertThat(result.chunks().get(1).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Abschn. Personalausweis beantragen › Voraussetzungen");
    assertThat(result.chunks().get(2).getText())
        .startsWith("Personalausweis beantragen › Voraussetzungen › Fuer Minderjaehrige")
        .contains("Erziehungsberechtigten");
    assertThat(result.chunks().get(2).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Abschn. Personalausweis beantragen › Voraussetzungen › Fuer Minderjaehrige");
    assertThat(result.chunks().get(3).getText())
        .startsWith("Personalausweis beantragen › Gebuehren")
        .contains("37,00 EUR");
    assertThat(result.chunks().get(3).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Abschn. Personalausweis beantragen › Gebuehren");
    // A key this pipeline actually produced that also belongs to the registry-wide passthrough
    // union must be part of its own declaration - storeChunks copies any union key it finds on a
    // chunk regardless of which pipeline declares it (nested-pipeline attribution), so an
    // undeclared union key here would silently ride along. A key outside the union is irrelevant:
    // storeChunks never copies it, declared or not.
    Set<String> actualKeysInUnion =
        result.chunks().stream()
            .flatMap(c -> c.getMetadata().keySet().stream())
            .filter(PassthroughMetadataKeysTestSupport.REGISTRY_UNION::contains)
            .collect(toSet());
    assertThat(pipeline.passthroughMetadataKeys()).containsAll(actualKeysInUnion);
  }

  @Test
  void aSiblingHeadingClosesTheDeeperOneItFollows() throws IOException {
    // "Gebuehren" is a sibling of "Voraussetzungen" (both h2), not a child of "Fuer
    // Minderjaehrige" (h3) - its own path must not still carry the h3 title.
    DocumentPipelineResult result = pipeline.run(sourceFor(REALISTIC_PAGE));

    String lastPath =
        (String) result.chunks().getLast().getMetadata().get(ChunkingService.LOCATION_METADATA_KEY);
    assertThat(lastPath).doesNotContain("Minderjaehrige");
  }

  @Test
  void aHeadingWithNoBodyTextStillBecomesItsOwnChunkInsteadOfNoExtractableText()
      throws IOException {
    // a section that is nothing but its own heading must not disappear -
    // it is real, searchable content even without a paragraph beneath it.
    String headingOnly = "<html><body><main><h1>Nur eine Ueberschrift</h1></main></body></html>";

    DocumentPipelineResult result = pipeline.run(sourceFor(headingOnly));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).isEqualTo("Nur eine Ueberschrift");
  }

  // --- header/footer nested inside the content area survive ----------

  @Test
  void headerAndFooterNestedInsideTheContentAreaAreNotStripped() throws IOException {
    String page =
        """
        <html>
          <body>
            <nav><a href="/">Startseite</a></nav>
            <article>
              <header>
                <h1>Verwaltungsgebuehrensatzung</h1>
                <p>Stand: 01.01.2026</p>
              </header>
              <p>Diese Satzung regelt die Gebuehren der Stadt.</p>
              <footer><p>Autor: Kaemmerei</p></footer>
            </article>
          </body>
        </html>
        """;

    DocumentPipelineResult result = pipeline.run(sourceFor(page));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    String allText = String.join("\n", result.chunks().stream().map(d -> d.getText()).toList());
    assertThat(allText)
        .contains("Stand: 01.01.2026")
        .contains("Autor: Kaemmerei")
        .doesNotContain("Startseite");
  }

  // --- every main/article match is processed, not just the first -------

  @Test
  void everyArticleOnAnOverviewPageIsProcessed() throws IOException {
    String overviewPage =
        """
        <html>
          <body>
            <nav><a href="/">Startseite</a></nav>
            <article><h2>Meldung eins</h2><p>Erster Teaser.</p></article>
            <article><h2>Meldung zwei</h2><p>Zweiter Teaser.</p></article>
          </body>
        </html>
        """;

    DocumentPipelineResult result = pipeline.run(sourceFor(overviewPage));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    String allText = String.join("\n", result.chunks().stream().map(d -> d.getText()).toList());
    assertThat(allText).contains("Erster Teaser").contains("Zweiter Teaser");
  }

  // --- a nested selector match is one root, not two ----------

  @Test
  void aMainWrappingAnArticleIsProcessedOnceNotTwice() throws IOException {
    // <main><article>…</article></main> matches MAIN_CONTENT_SELECTOR twice (both the main and
    // the article element) - the standard shape of many CMS templates. Only the outer match may
    // become a content root, or the same content is cut and stored twice.
    String page =
        "<html><body><main><article><h1>Titel</h1><p>Inhalt.</p></article></main></body></html>";

    DocumentPipelineResult result = pipeline.run(sourceFor(page));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).contains("Inhalt.");
  }

  // --- nav/cookie banner inside the content area still go ----

  @Test
  void navAndCookieBannerInsideTheContentAreaAreStillStripped() throws IOException {
    // Unlike header/footer (see headerAndFooterNestedInsideTheContentAreaAreNotStripped), nav,
    // aside and cookie-consent markers are never legitimate content - a CMS nesting them inside
    // its own <main>/<article> wrapper (a common pattern) must not let them survive just because
    // they sit inside the chosen content root.
    String page =
        """
        <html>
          <body>
            <main>
              <nav><a href="/">Startseite</a></nav>
              <div class="cookie-banner"><p>Wir verwenden Cookies.</p></div>
              <h1>Titel</h1>
              <p>Inhalt.</p>
            </main>
          </body>
        </html>
        """;

    DocumentPipelineResult result = pipeline.run(sourceFor(page));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    String allText = String.join("\n", result.chunks().stream().map(d -> d.getText()).toList());
    assertThat(allText).doesNotContain("Startseite").doesNotContain("Cookies").contains("Inhalt.");
  }

  // --- no redundant title-only chunk for an ordinary document -

  @Test
  void anOrdinaryTitleImmediatelyFollowedByASubsectionHeadingGetsNoRedundantTitleOnlyChunk()
      throws IOException {
    // h1 immediately followed by h2 (no body text of the h1's own in between) is the ordinary
    // shape of a titled document, not an empty section - it must not additionally produce a chunk
    // containing nothing but the page title. Contrast with
    // aHeadingWithNoBodyTextStillBecomesItsOwnChunkInsteadOfNoExtractableText above: a page that
    // truly is only headings still gets its one chunk.
    String page =
        "<html><body><main><h1>Titel</h1><h2>Abschnitt</h2><p>Text.</p></main></body></html>";

    DocumentPipelineResult result = pipeline.run(sourceFor(page));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText())
        .startsWith("Titel › Abschnitt")
        .contains("Text.");
  }

  // --- inline markup must not introduce a spurious word-internal space -

  @Test
  void inlineMarkupDoesNotIntroduceASpuriousSpaceInsideAWord() throws IOException {
    String page =
        "<html><body><main><h1>T</h1><p><b>Personal</b>ausweis beantragen</p></main></body></html>";

    DocumentPipelineResult result = pipeline.run(sourceFor(page));

    assertThat(result.chunks().getFirst().getText())
        .contains("Personalausweis beantragen")
        .doesNotContain("Personal ausweis");
  }

  @Test
  void inlineMarkupWithARealSpaceInTheSourceKeepsTheSpace() throws IOException {
    String page = "<html><body><main><h1>T</h1><p><b>Personal</b> ausweis</p></main></body></html>";

    DocumentPipelineResult result = pipeline.run(sourceFor(page));

    assertThat(result.chunks().getFirst().getText()).contains("Personal ausweis");
  }

  // --- tables, lists, preformatted text: the structure the shared walker keeps --------------

  @Test
  void tablesBecomeOneLinePerRowWithSeparatedCells() throws IOException {
    String page =
        """
        <html><body><main>
          <h1>Gebuehren</h1>
          <table>
            <tr><th>Leistung</th><th>Betrag</th></tr>
            <tr><td>Personalausweis</td><td>37,00 EUR</td></tr>
            <tr><td>Reisepass</td><td>70,00 EUR</td></tr>
          </table>
        </main></body></html>
        """;

    DocumentPipelineResult result = pipeline.run(sourceFor(page));

    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText())
        .isEqualTo(
            "Gebuehren\n\nLeistung | Betrag\n\nPersonalausweis | 37,00 EUR\n\n"
                + "Reisepass | 70,00 EUR");
  }

  @Test
  void listsKeepOneLinePerItemWithNestingCarriedByTheMarker() throws IOException {
    String page =
        """
        <html><body><main>
          <h1>Unterlagen</h1>
          <ul><li>Lichtbild</li><li>Nachweise<ul><li>Meldebescheinigung</li></ul></li></ul>
          <ol><li>Termin buchen</li><li>Vorsprechen</li></ol>
        </main></body></html>
        """;

    DocumentPipelineResult result = pipeline.run(sourceFor(page));

    assertThat(result.chunks().getFirst().getText())
        .isEqualTo(
            "Unterlagen\n\n• Lichtbild\n\n• Nachweise\n\n◦ Meldebescheinigung\n\n"
                + "1. Termin buchen\n\n2. Vorsprechen");
  }

  @Test
  void preformattedTextKeepsItsLineBreaksAndNonBreakingSpacesCollapse() throws IOException {
    String page =
        "<html><body><main><h1>T</h1><pre>Zeile 1\n  Zeile 2</pre>"
            + "<p>Frist:&nbsp;14&nbsp;Tage</p><p>&nbsp;</p></main></body></html>";

    DocumentPipelineResult result = pipeline.run(sourceFor(page));

    assertThat(result.chunks().getFirst().getText())
        .isEqualTo("T\n\nZeile 1\n  Zeile 2\n\nFrist: 14 Tage");
  }

  // --- Grenzfall: eine Seite, die ausschliesslich aus Boilerplate besteht --------------------

  @Test
  void aBoilerplateOnlyPageHasNoExtractableText() throws IOException {
    String boilerplateOnly =
        """
        <html>
          <body>
            <header><a href="/">Startseite</a></header>
            <nav><a href="/kontakt">Kontakt</a></nav>
            <div class="cookie-banner"><p>Wir verwenden Cookies.</p></div>
            <footer><p>Impressum</p></footer>
          </body>
        </html>
        """;

    DocumentPipelineResult result = pipeline.run(sourceFor(boilerplateOnly));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
    assertThat(result.chunks()).isEmpty();
  }

  // --- size control -------------------------------------------------

  @Test
  void manyParagraphsWithNoHeadingStructureAreSplitAtBlockBoundariesInsteadOfOneGiantChunk()
      throws IOException {
    // A "Div-Suppe" and a page whose §-style headings are just <p><strong> both share the same
    // shape: block-level text with no h1-h3 to cut on. Without the soft budget this becomes a
    // single ever-growing chunk; it must be split into several, ordinary-sized ones instead.
    StringBuilder body = new StringBuilder();
    for (int i = 1; i <= 150; i++) {
      body.append("<p>§ ").append(i).append(" Ein Absatz mit etwas Verwaltungstext hier drin.</p>");
    }
    String page = "<html><body><main>" + body + "</main></body></html>";

    DocumentPipelineResult result = pipeline.run(sourceFor(page));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSizeGreaterThanOrEqualTo(2);
    assertThat(result.chunks())
        .as("no chunk should silently grow past the soft budget by more than a single block")
        .allSatisfy(
            chunk ->
                assertThat(chunk.getText().length())
                    .isLessThan(HtmlDocumentPipeline.SOFT_CHUNK_CHAR_LIMIT + 200));
    // Nothing is lost across the split.
    String allText = String.join("\n", result.chunks().stream().map(d -> d.getText()).toList());
    assertThat(allText).contains("§ 1 ").contains("§ 150 ");
  }

  // --- Grenzfall: ein einzelner, riesiger Block ohne weitere Blockgrenzen (Backstop) ----------

  @Test
  void aSinglePathologicallyLargeBlockIsTruncatedAtTheHardCharacterLimitAsABackstop()
      throws IOException {
    // One giant paragraph with no internal block boundary at all - block splitting cannot help
    // here, so the hard limit is the only thing left to guard the embedding call.
    String hugeParagraph = "Verwaltungsvorgang. ".repeat(2_000); // well past 20 000 characters
    String hugePage =
        "<html><body><main><h1>Grosser Vorgang</h1><p>"
            + hugeParagraph
            + "</p></main></body></html>";

    DocumentPipelineResult result = pipeline.run(sourceFor(hugePage));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    String text = result.chunks().getFirst().getText();
    assertThat(text).hasSize(HtmlDocumentPipeline.HARD_CHUNK_CHAR_LIMIT);
    assertThat(text).endsWith("[…gekürzt]");
  }

  // --- extracted text: a feed entry's main content arrives as HTML without a file -------------

  @Test
  void runsAgainstExtractedTextToo() {
    DocumentPipelineResult result =
        pipeline.run(
            DocumentPipelineSource.ofExtractedText(
                "<html><body><main><h1>Titel</h1><p>Inhalt.</p></main></body></html>",
                "seite.html"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).contains("Inhalt.");
  }

  @Test
  void aMainContentFragmentWithoutAnEnclosingPageIsCutLikeAWholePage() {
    // The feed connector hands over the already reduced content roots, not a whole page.
    DocumentPipelineResult result =
        pipeline.run(
            DocumentPipelineSource.ofExtractedText(
                "<main><h1>Pressemitteilung</h1><p>Einleitung.</p><h2>Hintergrund</h2>"
                    + "<p>Details.</p></main>",
                "Pressemitteilung"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).extracting(d -> d.getText())
        .containsExactly(
            "Pressemitteilung\n\nEinleitung.",
            "Pressemitteilung › Hintergrund\n\nDetails.");
  }

  private DocumentPipelineSource sourceFor(String html) throws IOException {
    Path file = tempDir.resolve("seite.html");
    Files.writeString(file, html, StandardCharsets.UTF_8);
    return DocumentPipelineSource.ofFile(file, "seite.html", ".html");
  }
}
