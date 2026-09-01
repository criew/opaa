package io.opaa.indexing.pipeline.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ODP pipeline (#1110; ingestion-pipelines.md Teil 3 Punkt 2: "eine Folie = ein Chunk"):
 * mirrors PptxDocumentPipelineTest for the per-slide chunking rules, plus the ODS-style
 * XXE/zip-bomb guards {@code TabularDocumentPipelineTest} exercises for its own {@code content.xml}
 * reader.
 */
class OdpDocumentPipelineTest {

  @TempDir Path tempDir;

  private final OdpDocumentPipeline pipeline =
      new OdpDocumentPipeline(new OdfProperties(0, 0, 0, 0));

  @Test
  void claimsExactlyOdp() {
    assertThat(pipeline.handledFormats()).containsExactly(".odp");
    assertThat(pipeline.id()).isEqualTo("odp");
    assertThat(pipeline.version()).isEqualTo((short) 1);
  }

  @Test
  void oneChunkPerSlideCarryingTitleAndSlideNumber() throws IOException {
    Path file = tempDir.resolve("praesentation.odp");
    writeOdp(
        file,
        odpSlide(
                odpFrame("title", "Einfuehrung")
                    + odpFrame(null, "Willkommen zur Buergerversammlung."))
            + odpSlide(
                odpFrame("title", "Gebuehren")
                    + odpFrame(null, "Der Personalausweis kostet 37,00 EUR.")
                    + odpNotes("Bitte langsam sprechen.")));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "praesentation.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().get(0).getText())
        .startsWith("Einfuehrung")
        .contains("Buergerversammlung");
    assertThat(result.chunks().get(0).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Folie 1: Einfuehrung");
    assertThat(result.chunks().get(1).getText())
        .startsWith("Gebuehren")
        .contains("37,00 EUR")
        .contains("Notizen: Bitte langsam sprechen.");
    assertThat(result.chunks().get(1).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Folie 2: Gebuehren");
  }

  @Test
  void aBlankSlideAlongsideOthersStillBecomesItsOwnChunk() throws IOException {
    Path file = tempDir.resolve("mit-leerer-folie.odp");
    writeOdp(
        file,
        odpSlide(odpFrame("title", "Einfuehrung") + odpFrame(null, "Willkommen.")) + odpSlide(""));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "mit-leerer-folie.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().get(1).getText()).isEqualTo("Folie 2");
  }

  @Test
  void aPresentationWhereNoSlideHasAnyTextIsRejectedAsNoExtractableText() throws IOException {
    Path file = tempDir.resolve("nur-bilder.odp");
    writeOdp(file, odpSlide("") + odpSlide(""));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "nur-bilder.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void aTableIsReadRowByRowAndAddedAsBodyText() throws IOException {
    Path file = tempDir.resolve("tabelle.odp");
    writeOdp(
        file,
        odpSlide(
            "<draw:frame>"
                + odpTable(odpRow("Leistung", "Gebuehr"), odpRow("Personalausweis", "37,00 EUR"))
                + "</draw:frame>"));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "tabelle.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText())
        .contains("Leistung | Gebuehr")
        .contains("Personalausweis | 37,00 EUR");
  }

  @Test
  void multipleSpacesTabsAndLineBreaksAreRenderedRatherThanSwallowed() throws IOException {
    Path file = tempDir.resolve("ausrichtung.odp");
    writeOdp(
        file,
        odpSlide(
            odpFrame(null, "Personalausweis<text:tab/>37,00 EUR")
                + odpFrame(null, "Zeile eins<text:line-break/>Zeile zwei")
                + odpFrame(null, "A<text:s text:c=\"3\"/>B")));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "ausrichtung.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks().getFirst().getText())
        .contains("Personalausweis\t37,00 EUR")
        .contains("Zeile eins\nZeile zwei")
        .contains("A   B");
  }

  @Test
  void aNestedTableKeepsTheOuterTablesOwnRows() throws IOException {
    // regression guard for #1143: the carrier row (the row whose cell holds the nested table) has
    // a second cell of its own that must survive intact, and the nested table's own row must not
    // be mixed into the outer table.
    Path file = tempDir.resolve("verschachtelte-tabelle.odp");
    writeOdp(
        file,
        odpSlide(
            "<draw:frame>"
                + "<table:table>"
                + odpRow("Leistung", "Gebuehr")
                + "<table:table-row>"
                + "<table:table-cell>"
                + odpTable(odpRow("innen", "innen"))
                + "</table:table-cell>"
                + "<table:table-cell><text:p>Randnotiz</text:p></table:table-cell>"
                + "</table:table-row>"
                + odpRow("Personalausweis", "37,00 EUR")
                + "</table:table>"
                + "</draw:frame>"));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "verschachtelte-tabelle.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText())
        .contains("Leistung | Gebuehr")
        .contains(" | Randnotiz")
        .contains("Personalausweis | 37,00 EUR")
        .doesNotContain("innen");
  }

  @Test
  void aNonContentNotesPlaceholderIsExcludedFromTheNotesText() throws IOException {
    // Mirrors PptxDocumentPipeline's NON_CONTENT_NOTES_PLACEHOLDERS: layout scaffolding
    // (slide number/date/footer/header) inherited from the notes master is never meaningful body
    // text.
    Path file = tempDir.resolve("notiz-platzhalter.odp");
    writeOdp(
        file,
        odpSlide(
            odpFrame(null, "Folieninhalt.")
                + "<presentation:notes><draw:page>"
                + odpFrame("notes", "Echte Notiz.")
                + odpFrame("footer", "Seite 1 von 3")
                + "</draw:page></presentation:notes>"));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "notiz-platzhalter.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks().getFirst().getText())
        .contains("Notizen:")
        .contains("Echte Notiz.")
        .doesNotContain("Seite 1 von 3");
  }

  @Test
  void aPresentationWithoutAnySlideIsRejectedAsNoExtractableText() throws IOException {
    // A well-formed but empty <office:presentation/> is a parsed document with nothing to chunk,
    // not an unparseable one - distinct from aZipWithoutAContentXmlEntryHasNoContent below. Matches
    // the NO_EXTRACTABLE_TEXT outcome TikaFallbackPipeline reported for this exact case before this
    // pipeline existed (#1057).
    Path file = tempDir.resolve("keine-folien.odp");
    writeOdp(file, "");

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "keine-folien.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void aZipWithoutAContentXmlEntryHasNoContent() throws IOException {
    Path file = tempDir.resolve("ohne-content-xml.odp");
    try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
      out.putNextEntry(new ZipEntry("mimetype"));
      out.write("application/vnd.oasis.opendocument.presentation".getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "ohne-content-xml.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
  }

  @Test
  void aFilelessSourceHasNoContent() {
    // An ODP pipeline is only ever reached through a genuine .odp file (never RSS-extracted text,
    // ADR-0017 decision 2) - defensive fallback, mirrors DocxDocumentPipeline/PptxDocumentPipeline.
    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofExtractedText("irrelevanter Text", "quelle.odp"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void aFileThatIsNotAValidZipArchiveThrows() throws IOException {
    Path file = tempDir.resolve("kaputt.odp");
    Files.writeString(file, "das ist kein odp");

    assertThatThrownBy(
            () -> pipeline.run(DocumentPipelineSource.ofFile(file, "kaputt.odp", ".odp")))
        .isInstanceOf(UncheckedIOException.class);
  }

  @Test
  void aContentXmlWithADoctypeIsRejectedRatherThanResolvingExternalEntities() throws IOException {
    // XXE hardening: content.xml comes from an uploaded/indexed file, never trusted input.
    Path file = tempDir.resolve("xxe.odp");
    String maliciousContent =
        "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE office:document-content [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + "<office:document-content"
            + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
            + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
            + " xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\""
            + " xmlns:presentation=\"urn:oasis:names:tc:opendocument:xmlns:presentation:1.0\">"
            + "<office:body><office:presentation>"
            + odpSlide(odpFrame(null, "&xxe;"))
            + "</office:presentation></office:body></office:document-content>";
    try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
      out.putNextEntry(new ZipEntry("content.xml"));
      out.write(maliciousContent.getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }

    assertThatThrownBy(() -> pipeline.run(DocumentPipelineSource.ofFile(file, "xxe.odp", ".odp")))
        .isInstanceOf(UncheckedIOException.class);
  }

  @Test
  void aContentXmlExceedingTheByteLimitIsRejectedRatherThanExhaustingMemory() throws IOException {
    OdpDocumentPipeline tinyLimitPipeline = new OdpDocumentPipeline(new OdfProperties(50, 0, 0, 0));
    Path file = tempDir.resolve("gross.odp");
    writeOdp(file, odpSlide(odpFrame("title", "Titel") + odpFrame(null, "Ein laengerer Text.")));

    assertThatThrownBy(
            () -> tinyLimitPipeline.run(DocumentPipelineSource.ofFile(file, "gross.odp", ".odp")))
        .isInstanceOf(UncheckedIOException.class)
        .rootCause()
        .hasMessageContaining("size limit");
  }

  @Test
  void aTextSWithAnExtremeRepeatCountIsCappedRatherThanExhaustingMemory() throws IOException {
    // regression guard for #1143: text:c is attacker-controlled and unrelated to content.xml's
    // byte size - without a cap, a single element requests gigabytes of in-memory spaces.
    OdpDocumentPipeline tinyLimitPipeline = new OdpDocumentPipeline(new OdfProperties(0, 0, 0, 5));
    Path file = tempDir.resolve("weite-luecke.odp");
    writeOdp(file, odpSlide(odpFrame(null, "A<text:s text:c=\"2000000000\"/>B")));

    DocumentPipelineResult result =
        tinyLimitPipeline.run(DocumentPipelineSource.ofFile(file, "weite-luecke.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks().getFirst().getText()).contains("A     B");
  }

  @Test
  void aPresentationExceedingTheSlideLimitIsRejectedRatherThanExhaustingMemory()
      throws IOException {
    OdpDocumentPipeline tinyLimitPipeline = new OdpDocumentPipeline(new OdfProperties(0, 0, 1, 0));
    Path file = tempDir.resolve("viele-folien.odp");
    writeOdp(
        file, odpSlide(odpFrame(null, "Folie eins.")) + odpSlide(odpFrame(null, "Folie zwei.")));

    assertThatThrownBy(
            () ->
                tinyLimitPipeline.run(
                    DocumentPipelineSource.ofFile(file, "viele-folien.odp", ".odp")))
        .isInstanceOf(UncheckedIOException.class)
        .rootCause()
        .hasMessageContaining("slide limit");
  }

  private static void writeOdp(Path file, String presentationBodyXml) throws IOException {
    String content =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<office:document-content"
            + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
            + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
            + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\""
            + " xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\""
            + " xmlns:presentation=\"urn:oasis:names:tc:opendocument:xmlns:presentation:1.0\">"
            + "<office:body><office:presentation>"
            + presentationBodyXml
            + "</office:presentation></office:body></office:document-content>";
    try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
      out.putNextEntry(new ZipEntry("content.xml"));
      out.write(content.getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
  }

  private static String odpSlide(String innerXml) {
    return "<draw:page>" + innerXml + "</draw:page>";
  }

  private static String odpFrame(String presentationClass, String text) {
    String classAttribute =
        presentationClass == null ? "" : " presentation:class=\"" + presentationClass + "\"";
    return "<draw:frame"
        + classAttribute
        + "><draw:text-box><text:p>"
        + text
        + "</text:p></draw:text-box></draw:frame>";
  }

  private static String odpNotes(String text) {
    return "<presentation:notes><draw:page>"
        + odpFrame("notes", text)
        + "</draw:page></presentation:notes>";
  }

  private static String odpTable(String... rows) {
    StringBuilder xml = new StringBuilder("<table:table>");
    for (String row : rows) {
      xml.append(row);
    }
    return xml.append("</table:table>").toString();
  }

  private static String odpRow(String... cellValues) {
    StringBuilder xml = new StringBuilder("<table:table-row>");
    for (String value : cellValues) {
      xml.append("<table:table-cell><text:p>").append(value).append("</text:p></table:table-cell>");
    }
    return xml.append("</table:table-row>").toString();
  }
}
