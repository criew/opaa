package io.opaa.indexing.pipeline.office;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.PassthroughMetadataKeysTestSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The ODT pipeline (#1110; ingestion-pipelines.md Teil 3 Punkt 2): the cut follows {@code text:h}'s
 * own {@code text:outline-level}, mirroring DocxDocumentPipelineTest for the same section-building
 * rules, plus the ODS-style XXE/zip-bomb guards {@code TabularDocumentPipelineTest} exercises for
 * its own {@code content.xml} reader.
 */
class OdtDocumentPipelineTest {

  @TempDir Path tempDir;

  private final OdtDocumentPipeline pipeline =
      new OdtDocumentPipeline(new OdfProperties(0, 0, 0, 0, 0));

  @Test
  void claimsExactlyOdt() {
    assertThat(pipeline.handledFormats()).containsExactly(".odt");
    assertThat(pipeline.id()).isEqualTo("odt");
    assertThat(pipeline.version()).isEqualTo((short) 1);
  }

  @Test
  void cutsFollowOutlineLevelsWithOneChunkPerSectionAndTheHeadingInTheChunkText()
      throws IOException {
    Path file = tempDir.resolve("satzung.odt");
    writeOdt(
        file,
        odtHeading(1, "Verwaltungsgebuehrensatzung")
            + odtParagraph("Diese Satzung regelt die Gebuehren der Stadt.")
            + odtHeading(2, "Personaldokumente")
            + odtParagraph(
                "Fuer die Ausstellung eines Personalausweises werden Gebuehren erhoben."));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "satzung.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().get(0).getText())
        .startsWith("Verwaltungsgebuehrensatzung")
        .contains("regelt die Gebuehren");
    assertThat(result.chunks().get(0).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Abschn. Verwaltungsgebuehrensatzung");
    assertThat(result.chunks().get(1).getText())
        .startsWith("Verwaltungsgebuehrensatzung › Personaldokumente")
        .contains("Personalausweises");
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
  void aHeadingWithoutAnOutlineLevelAttributeDefaultsToLevelOne() throws IOException {
    Path file = tempDir.resolve("ohne-level.odt");
    writeOdt(file, "<text:h>Kapitel eins</text:h>" + odtParagraph("Text im ersten Kapitel."));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "ohne-level.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText())
        .startsWith("Kapitel eins")
        .contains("Text im ersten Kapitel");
  }

  @Test
  void aTableIsReadCellByCellAndAddedAsBodyText() throws IOException {
    Path file = tempDir.resolve("gebuehrenverzeichnis.odt");
    writeOdt(
        file,
        odtHeading(1, "Gebuehrenverzeichnis")
            + odtTable(odtRow("Leistung", "Gebuehr"), odtRow("Personalausweis", "37,00 EUR")));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "gebuehrenverzeichnis.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText())
        .startsWith("Gebuehrenverzeichnis")
        .contains("Leistung | Gebuehr")
        .contains("Personalausweis | 37,00 EUR");
  }

  @Test
  void textInsideANestedSpanIsStillCaptured() throws IOException {
    Path file = tempDir.resolve("formatiert.odt");
    writeOdt(
        file, "<text:p>Teil A <text:span text:style-name=\"T1\">fett</text:span> Teil B</text:p>");

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "formatiert.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks().getFirst().getText()).isEqualTo("Teil A fett Teil B");
  }

  @Test
  void multipleSpacesTabsAndLineBreaksAreRenderedRatherThanSwallowed() throws IOException {
    Path file = tempDir.resolve("ausrichtung.odt");
    writeOdt(
        file,
        "<text:p>Personalausweis<text:tab/>37,00 EUR</text:p>"
            + "<text:p>Zeile eins<text:line-break/>Zeile zwei</text:p>"
            + "<text:p>A<text:s text:c=\"3\"/>B</text:p>");

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "ausrichtung.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks().getFirst().getText())
        .contains("Personalausweis\t37,00 EUR")
        .contains("Zeile eins\nZeile zwei")
        .contains("A   B");
  }

  @Test
  void deletedTextInTrackedChangesIsNotIndexedAsCurrentBodyText() throws IOException {
    Path file = tempDir.resolve("aenderungsverfolgung.odt");
    writeOdt(
        file,
        "<text:tracked-changes>"
            + "<text:changed-region text:id=\"ct1\">"
            + "<text:deletion><text:p>Geloeschter Absatz.</text:p></text:deletion>"
            + "</text:changed-region>"
            + "</text:tracked-changes>"
            + odtHeading(1, "Ueberschrift")
            + odtParagraph("Aktueller Absatz."));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "aenderungsverfolgung.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText())
        .contains("Aktueller Absatz.")
        .doesNotContain("Geloeschter Absatz.");
  }

  @Test
  void aNestedTableKeepsTheOuterTablesOwnRows() throws IOException {
    // regression guard for #1143: the carrier row (the row whose cell holds the nested table) has
    // a second cell of its own that must survive intact, and the nested table's own row must not
    // be mixed into the outer table.
    Path file = tempDir.resolve("verschachtelte-tabelle.odt");
    writeOdt(
        file,
        odtHeading(1, "Gebuehrenverzeichnis")
            + "<table:table>"
            + odtRow("Leistung", "Gebuehr")
            + "<table:table-row>"
            + "<table:table-cell>"
            + odtTable(odtRow("innen", "innen"))
            + "</table:table-cell>"
            + "<table:table-cell><text:p>Randnotiz</text:p></table:table-cell>"
            + "</table:table-row>"
            + odtRow("Personalausweis", "37,00 EUR")
            + "</table:table>");

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "verschachtelte-tabelle.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText())
        .contains("Leistung | Gebuehr")
        .contains(" | Randnotiz")
        .contains("Personalausweis | 37,00 EUR")
        .doesNotContain("innen");
  }

  @Test
  void aFilelessSourceHasNoContent() {
    // An ODT pipeline is only ever reached through a genuine .odt file (never RSS-extracted text,
    // ADR-0017 decision 2) - defensive fallback, mirrors DocxDocumentPipeline/PptxDocumentPipeline.
    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofExtractedText("irrelevanter Text", "quelle.odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void aHeadingWithNoBodyTextStillBecomesItsOwnChunkInsteadOfNoExtractableText()
      throws IOException {
    Path file = tempDir.resolve("nur-ueberschrift.odt");
    writeOdt(file, odtHeading(1, "Nur eine Ueberschrift"));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "nur-ueberschrift.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).isEqualTo("Nur eine Ueberschrift");
  }

  @Test
  void aDocumentWithoutAnyTextIsRejectedAsNoExtractableText() throws IOException {
    // A well-formed but empty <office:text/> is a parsed document with nothing to chunk, not an
    // unparseable one - distinct from aZipWithoutAContentXmlEntryHasNoContent below. Matches the
    // NO_EXTRACTABLE_TEXT outcome TikaFallbackPipeline reported for this exact case before this
    // pipeline existed (#1057), so an empty document's skipped-not-failed treatment is unchanged.
    Path file = tempDir.resolve("leer.odt");
    writeOdt(file, "");

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "leer.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void aZipWithoutAContentXmlEntryHasNoContent() throws IOException {
    Path file = tempDir.resolve("ohne-content-xml.odt");
    try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
      out.putNextEntry(new ZipEntry("mimetype"));
      out.write("application/vnd.oasis.opendocument.text".getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "ohne-content-xml.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
  }

  @Test
  void aFileThatIsNotAValidZipArchiveHasNoContent() throws IOException {
    Path file = tempDir.resolve("kaputt.odt");
    Files.writeString(file, "das ist kein odt");

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "kaputt.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
  }

  @Test
  void aContentXmlWithADoctypeIsRejectedRatherThanResolvingExternalEntities() throws IOException {
    // XXE hardening: content.xml comes from an uploaded/indexed file, never trusted input.
    Path file = tempDir.resolve("xxe.odt");
    String maliciousContent =
        "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE office:document-content [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + "<office:document-content"
            + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
            + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\">"
            + "<office:body><office:text><text:p>&xxe;</text:p></office:text></office:body>"
            + "</office:document-content>";
    try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
      out.putNextEntry(new ZipEntry("content.xml"));
      out.write(maliciousContent.getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "xxe.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
  }

  @Test
  void aContentXmlExceedingTheByteLimitIsRejectedRatherThanExhaustingMemory() throws IOException {
    OdtDocumentPipeline tinyLimitPipeline =
        new OdtDocumentPipeline(new OdfProperties(50, 0, 0, 0, 0));
    Path file = tempDir.resolve("gross.odt");
    writeOdt(file, odtHeading(1, "Ueberschrift") + odtParagraph("Ein laengerer Textkoerper."));

    DocumentPipelineResult result =
        tinyLimitPipeline.run(DocumentPipelineSource.ofFile(file, "gross.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
  }

  @Test
  void theByteLimitDirectlyThrowsAnIOExceptionNamingWhichLimitWasHit() throws IOException {
    // #1108 review, finding 4: the pipeline's own catch-all collapses every parse failure into the
    // same NO_CONTENT outcome, so a wrong-reason failure would stay green there. This test goes
    // straight at OdfContentXml.parse instead, the one place the byte limit's own message survives.
    Path file = tempDir.resolve("gross-direkt.odt");
    writeOdt(file, odtHeading(1, "Ueberschrift") + odtParagraph("Ein laengerer Textkoerper."));

    assertThatThrownBy(() -> OdfContentXml.parse(file, 50, new DefaultHandler()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("size limit");
  }

  @Test
  void aTextSWithAnExtremeRepeatCountIsCappedRatherThanExhaustingMemory() throws IOException {
    // regression guard for #1143: text:c is attacker-controlled and unrelated to content.xml's
    // byte size - without a cap, a single element requests gigabytes of in-memory spaces.
    OdtDocumentPipeline tinyLimitPipeline =
        new OdtDocumentPipeline(new OdfProperties(0, 0, 0, 5, 0));
    Path file = tempDir.resolve("weite-luecke.odt");
    writeOdt(file, odtParagraph("A<text:s text:c=\"2000000000\"/>B"));

    DocumentPipelineResult result =
        tinyLimitPipeline.run(DocumentPipelineSource.ofFile(file, "weite-luecke.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks().getFirst().getText()).isEqualTo("A     B");
  }

  @Test
  void
      manyTextSElementsInOneParagraphAreRejectedByTheCumulativeCharacterBudgetRatherThanExhaustingMemory()
          throws IOException {
    // regression guard for #1143: the per-element cap (maxSpaceRepeat) bounds a single text:s
    // element, but text is only reset once per paragraph - an unbounded number of text:s elements
    // inside the same <text:p> would otherwise sum into the same buffer without limit.
    OdtDocumentPipeline tinyLimitPipeline =
        new OdtDocumentPipeline(new OdfProperties(0, 0, 0, 5, 12));
    Path file = tempDir.resolve("viele-leerzeichen.odt");
    writeOdt(
        file,
        odtParagraph(
            "<text:s text:c=\"5\"/><text:s text:c=\"5\"/><text:s text:c=\"5\"/>"
                + "<text:s text:c=\"5\"/>"));

    DocumentPipelineResult result =
        tinyLimitPipeline.run(DocumentPipelineSource.ofFile(file, "viele-leerzeichen.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
  }

  @Test
  void theTextCharacterBudgetDirectlyThrowsASaxExceptionNamingWhichLimitWasHit()
      throws IOException {
    // See theByteLimitDirectlyThrowsAnIOExceptionNamingWhichLimitWasHit's own Javadoc.
    Path file = tempDir.resolve("viele-leerzeichen-direkt.odt");
    writeOdt(
        file,
        odtParagraph(
            "<text:s text:c=\"5\"/><text:s text:c=\"5\"/><text:s text:c=\"5\"/>"
                + "<text:s text:c=\"5\"/>"));
    OdtDocumentPipeline.OdtContentHandler handler =
        new OdtDocumentPipeline.OdtContentHandler(50_000, 5, 12);

    assertThatThrownBy(() -> OdfContentXml.parse(file, 10_485_760L, handler))
        .isInstanceOf(IOException.class)
        .rootCause()
        .hasMessageContaining("text character limit");
  }

  @Test
  void aDocumentExceedingTheParagraphLimitIsRejectedRatherThanExhaustingMemory()
      throws IOException {
    OdtDocumentPipeline tinyLimitPipeline =
        new OdtDocumentPipeline(new OdfProperties(0, 1, 0, 0, 0));
    Path file = tempDir.resolve("viele-absaetze.odt");
    writeOdt(file, odtParagraph("Erster Absatz.") + odtParagraph("Zweiter Absatz."));

    DocumentPipelineResult result =
        tinyLimitPipeline.run(DocumentPipelineSource.ofFile(file, "viele-absaetze.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
  }

  @Test
  void theParagraphLimitDirectlyThrowsASaxExceptionNamingWhichLimitWasHit() throws IOException {
    // See theByteLimitDirectlyThrowsAnIOExceptionNamingWhichLimitWasHit's own Javadoc.
    Path file = tempDir.resolve("viele-absaetze-direkt.odt");
    writeOdt(file, odtParagraph("Erster Absatz.") + odtParagraph("Zweiter Absatz."));
    OdtDocumentPipeline.OdtContentHandler handler =
        new OdtDocumentPipeline.OdtContentHandler(1, 1_000, 10_000_000L);

    assertThatThrownBy(() -> OdfContentXml.parse(file, 10_485_760L, handler))
        .isInstanceOf(IOException.class)
        .rootCause()
        .hasMessageContaining("paragraph limit");
  }

  private static void writeOdt(Path file, String textBodyXml) throws IOException {
    String content =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<office:document-content"
            + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
            + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
            + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\">"
            + "<office:body><office:text>"
            + textBodyXml
            + "</office:text></office:body></office:document-content>";
    try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
      out.putNextEntry(new ZipEntry("content.xml"));
      out.write(content.getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
  }

  private static String odtHeading(int level, String text) {
    return "<text:h text:outline-level=\"" + level + "\">" + text + "</text:h>";
  }

  private static String odtParagraph(String text) {
    return "<text:p>" + text + "</text:p>";
  }

  private static String odtTable(String... rows) {
    StringBuilder xml = new StringBuilder("<table:table>");
    for (String row : rows) {
      xml.append(row);
    }
    return xml.append("</table:table>").toString();
  }

  private static String odtRow(String... cellValues) {
    StringBuilder xml = new StringBuilder("<table:table-row>");
    for (String value : cellValues) {
      xml.append("<table:table-cell><text:p>").append(value).append("</text:p></table:table-cell>");
    }
    return xml.append("</table:table-row>").toString();
  }
}
