package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The HTML pipeline (#1059; ingestion-pipelines.md Teil 3, Punkt 4): boilerplate (navigation,
 * footer, cookie banner) never reaches a chunk, and the cut follows h1-h3 with every chunk carrying
 * its heading path.
 */
class HtmlDocumentPipelineTest {

  @TempDir Path tempDir;

  private final HtmlDocumentPipeline pipeline = new HtmlDocumentPipeline();

  @Test
  void claimsExactlyHtml() {
    assertThat(pipeline.handledFormats()).containsExactly(".html");
    assertThat(pipeline.id()).isEqualTo("html");
    assertThat(pipeline.version()).isEqualTo((short) 1);
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
  void boilerplateNeverReachesAChunk() throws IOException {
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
  void cutsFollowH1ToH3WithOneChunkPerSection() throws IOException {
    DocumentPipelineResult result = pipeline.run(sourceFor(REALISTIC_PAGE));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    // Personalausweis (h1), Voraussetzungen (h2), Fuer Minderjaehrige (h3), Gebuehren (h2).
    assertThat(result.chunks()).hasSize(4);
    assertThat(result.chunks().get(0).getText()).contains("amtliches Ausweisdokument");
    assertThat(result.chunks().get(0).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Abschn. Personalausweis beantragen");
    assertThat(result.chunks().get(1).getText()).contains("biometrisches Lichtbild");
    assertThat(result.chunks().get(1).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Abschn. Personalausweis beantragen › Voraussetzungen");
    assertThat(result.chunks().get(2).getText()).contains("Erziehungsberechtigten");
    assertThat(result.chunks().get(2).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Abschn. Personalausweis beantragen › Voraussetzungen › Fuer Minderjaehrige");
    assertThat(result.chunks().get(3).getText()).contains("37,00 EUR");
    assertThat(result.chunks().get(3).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Abschn. Personalausweis beantragen › Gebuehren");
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

  // --- Grenzfall: eine riesige Seite ohne weitere Gliederung ----------------------------------

  @Test
  void aGiantSectionIsTruncatedAtTheHardCharacterLimit() throws IOException {
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

  // --- Formaterkennung: das Routing landet auf dieser Pipeline für sowohl Datei als auch bereits
  // extrahierten Text (letzteres nur zu Testzwecken - der reguläre Weg liefert immer eine Datei) --

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

  private DocumentPipelineSource sourceFor(String html) throws IOException {
    Path file = tempDir.resolve("seite.html");
    Files.writeString(file, html, StandardCharsets.UTF_8);
    return DocumentPipelineSource.ofFile(file, "seite.html", ".html");
  }
}
