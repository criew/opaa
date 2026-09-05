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
 * The ODT pipeline (ingestion-pipelines.md Teil 3 Punkt 2): the cut follows {@code text:h}'s own
 * {@code text:outline-level}, mirroring DocxDocumentPipelineTest for the same section-building
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
    assertThat(pipeline.version()).isEqualTo((short) 2);
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
  void headerAndFooterTextFromStylesXmlBecomeOneDeduplicatedLeadingChunk() throws IOException {
    // regression guard for #1145: an authority name/Aktenzeichen placed exclusively in the
    // Kopf-/Fußzeile must still be lexically searchable, and only once - not per page and not
    // dropped, as it would be without reading styles.xml at all.
    Path file = tempDir.resolve("mit-kopfzeile.odt");
    writeOdtWithStyles(
        file,
        odtHeading(1, "Antrag") + odtParagraph("Fachlicher Inhalt des Antrags."),
        "<style:header><text:p>Stadt Musterstadt</text:p></style:header>"
            + "<style:footer><text:p>Az. 12-34/2026</text:p></style:footer>");

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "mit-kopfzeile.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().getFirst().getText())
        .contains("Stadt Musterstadt")
        .contains("Az. 12-34/2026");
    assertThat(result.chunks().getFirst().getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Kopf-/Fußzeile");
    assertThat(result.chunks().get(1).getText()).startsWith("Antrag");
  }

  @Test
  void identicalHeaderVariantsContributeOnlyOnce() throws IOException {
    // regression guard for #1145: a handler concatenating every header/footer variant's text
    // unconditionally would dilute the embedding with the same authority name repeated across the
    // default/left/first variants.
    Path file = tempDir.resolve("kopfzeile-varianten.odt");
    writeOdtWithStyles(
        file,
        odtParagraph("Fachlicher Inhalt."),
        "<style:header><text:p>Stadt Musterstadt</text:p></style:header>"
            + "<style:header-left><text:p>Stadt Musterstadt</text:p></style:header-left>"
            + "<style:header-first><text:p>Stadt Musterstadt</text:p></style:header-first>");

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "kopfzeile-varianten.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    long occurrences =
        result.chunks().getFirst().getText().split("Stadt Musterstadt", -1).length - 1;
    assertThat(occurrences).isEqualTo(1);
  }

  @Test
  void aNonBreakingSpaceVariantIsDeduplicatedAgainstThePlainSpaceVariant() throws IOException {
    // regression guard for #1145: a non-breaking space (U+00A0) is routine in
    // an authority letterhead's column separators; \s alone does not match it, so a variant using
    // NBSP and the default using a plain space would otherwise both survive deduplication.
    Path file = tempDir.resolve("geschuetztes-leerzeichen.odt");
    writeOdtWithStyles(
        file,
        odtParagraph("Fachlicher Inhalt."),
        "<style:header><text:p>Stadt Musterstadt</text:p></style:header>"
            + "<style:header-left><text:p>Stadt\u00A0Musterstadt</text:p></style:header-left>");

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "geschuetztes-leerzeichen.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    long occurrences = result.chunks().getFirst().getText().split("Stadt", -1).length - 1;
    assertThat(occurrences).isEqualTo(1);
  }

  @Test
  void aFirstPageOnlyHeaderIsStillIndexed() throws IOException {
    // regression guard for #1145: "Erste Seite anders" (w:titlePg's ODF counterpart) is
    // the common German-authority-letterhead layout - the letterhead lives exclusively in
    // style:header-first, never in style:header. A handler reading only style:header would miss
    // exactly that case.
    Path file = tempDir.resolve("nur-erste-seite.odt");
    writeOdtWithStyles(
        file,
        odtParagraph("Fachlicher Inhalt."),
        "<style:header-first><text:p>Stadt Musterstadt</text:p></style:header-first>");

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "nur-erste-seite.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().getFirst().getText()).isEqualTo("Stadt Musterstadt");
  }

  @Test
  void multipleMasterPagesWithAnIdenticalFooterContributeOnlyOnce() throws IOException {
    // regression guard for #1145: "Erste Seite" and "Standard" master pages sharing the
    // same footer text is the common case a real ODT template produces.
    Path file = tempDir.resolve("mehrere-seitenvorlagen.odt");
    String masterStyles =
        "<style:master-page style:name=\"Standard\">"
            + "<style:footer><text:p>Stadt Musterstadt</text:p></style:footer>"
            + "</style:master-page>"
            + "<style:master-page style:name=\"Erste_20_Seite\">"
            + "<style:footer><text:p>Stadt Musterstadt</text:p></style:footer>"
            + "</style:master-page>";
    writeOdtWithRawStyles(
        file, odtParagraph("Fachlicher Inhalt."), wrapOdtMasterStyles(masterStyles));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "mehrere-seitenvorlagen.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    long occurrences =
        result.chunks().getFirst().getText().split("Stadt Musterstadt", -1).length - 1;
    assertThat(occurrences).isEqualTo(1);
  }

  @Test
  void aPageNumberFieldInTheFooterIsExcludedButSurroundingTextIsKept() throws IOException {
    // regression guard for #1145: a field's cached last-computed value (here the page
    // number) is wrong for every page but the one it was current on, and must not become indexed
    // content - the surrounding static text ("Seite ") is real content and must survive.
    Path file = tempDir.resolve("seitenzahl-fusszeile.odt");
    writeOdtWithStyles(
        file,
        odtParagraph("Fachlicher Inhalt."),
        "<style:footer><text:p>Seite <text:page-number>1</text:page-number></text:p></style:footer>");

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "seitenzahl-fusszeile.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().getFirst().getText()).isEqualTo("Seite");
  }

  @Test
  void aFooterThatIsOnlyAPageNumberFieldContributesNoLeadingChunkAtAll() throws IOException {
    // regression guard for #1145: once the field value is excluded,
    // nothing but digits/whitespace is left - RepeatingHeaderChunk's letter check must reject it
    // rather than index a chunk of pure noise.
    Path file = tempDir.resolve("nur-seitenzahl.odt");
    writeOdtWithStyles(
        file,
        odtParagraph("Fachlicher Inhalt."),
        "<style:footer><text:p><text:page-number>1</text:page-number></text:p></style:footer>");

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "nur-seitenzahl.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).startsWith("Fachlicher Inhalt");
  }

  @Test
  void headerFooterTextAloneDoesNotDefeatTheScanEmptyDeckGuard() throws IOException {
    // regression guard for #1145: a scanned authority letter carries its
    // letterhead in the Kopf-/Fusszeile just like a text-layer document, so header/footer text is
    // no evidence this document itself has extractable content. Adding the header/footer chunk
    // before the guard check would report CHUNKED instead of NO_EXTRACTABLE_TEXT - silently
    // reopening the stille-Leer-Index-Fehlfunktion this guard exists to prevent.
    Path file = tempDir.resolve("nur-kopfzeile.odt");
    writeOdtWithStyles(file, "", "<style:header><text:p>Stadt Musterstadt</text:p></style:header>");

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "nur-kopfzeile.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void aStylesXmlWithADoctypeOnlyForfeitsTheHeaderFooterChunkNotTheWholeDocument()
      throws IOException {
    // XXE hardening applies to styles.xml exactly as it does to content.xml - but unlike
    // content.xml, styles.xml is supplementary: a rejected styles.xml must not fail a document
    // whose content.xml parsed successfully.
    Path file = tempDir.resolve("xxe-styles.odt");
    String maliciousStyles =
        "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE office:document-styles [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + "<office:document-styles"
            + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
            + " xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\""
            + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\">"
            + "<office:master-styles><style:master-page>"
            + "<style:header><text:p>&xxe;</text:p></style:header>"
            + "</style:master-page></office:master-styles>"
            + "</office:document-styles>";
    writeOdtWithRawStyles(file, odtHeading(1, "Titel") + odtParagraph("Inhalt."), maliciousStyles);

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "xxe-styles.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).startsWith("Titel");
  }

  @Test
  void aStylesXmlExceedingTheByteLimitOnlyForfeitsTheHeaderFooterChunkNotTheWholeDocument()
      throws IOException {
    // regression guard for #1145.
    OdtDocumentPipeline tinyStylesLimitPipeline =
        new OdtDocumentPipeline(new OdfProperties(500, 0, 0, 0, 0));
    Path file = tempDir.resolve("grosse-styles.odt");
    writeOdtWithStyles(
        file,
        odtHeading(1, "Titel") + odtParagraph("Inhalt."),
        "<style:header><text:p>Ein ziemlich langer Kopfzeilentext, der den winzigen"
            + " Byte-Deckel dieses Tests sicher \u00fcbersteigt und damit den styles.xml-Parse"
            + " zum Scheitern bringt.</text:p></style:header>");

    DocumentPipelineResult result =
        tinyStylesLimitPipeline.run(
            DocumentPipelineSource.ofFile(file, "grosse-styles.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).startsWith("Titel");
  }

  @Test
  void aFilelessSourceIsAParseFailure() {
    // An ODT pipeline is only ever reached through a genuine .odt file (never RSS-extracted text,
    // ADR-0017 decision 2) - defensive fallback, mirrors DocxDocumentPipeline/PptxDocumentPipeline.
    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofExtractedText("irrelevanter Text", "quelle.odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.PARSE_FAILED);
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
    // unparseable one - distinct from aZipWithoutAContentXmlEntryIsAParseFailure below. Same
    // NO_EXTRACTABLE_TEXT outcome TikaFallbackPipeline reports for this case, so an empty
    // document stays skipped rather than failed.
    Path file = tempDir.resolve("leer.odt");
    writeOdt(file, "");

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "leer.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void aZipWithoutAContentXmlEntryIsAParseFailure() throws IOException {
    Path file = tempDir.resolve("ohne-content-xml.odt");
    try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
      out.putNextEntry(new ZipEntry("mimetype"));
      out.write("application/vnd.oasis.opendocument.text".getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "ohne-content-xml.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.PARSE_FAILED);
  }

  @Test
  void aFileThatIsNotAValidZipArchiveIsAParseFailure() throws IOException {
    Path file = tempDir.resolve("kaputt.odt");
    Files.writeString(file, "das ist kein odt");

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "kaputt.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.PARSE_FAILED);
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

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.PARSE_FAILED);
  }

  @Test
  void aContentXmlExceedingTheByteLimitIsRejectedRatherThanExhaustingMemory() throws IOException {
    OdtDocumentPipeline tinyLimitPipeline =
        new OdtDocumentPipeline(new OdfProperties(50, 0, 0, 0, 0));
    Path file = tempDir.resolve("gross.odt");
    writeOdt(file, odtHeading(1, "Ueberschrift") + odtParagraph("Ein laengerer Textkoerper."));

    DocumentPipelineResult result =
        tinyLimitPipeline.run(DocumentPipelineSource.ofFile(file, "gross.odt", ".odt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.PARSE_FAILED);
  }

  @Test
  void theByteLimitDirectlyThrowsAnIOExceptionNamingWhichLimitWasHit() throws IOException {
    // the pipeline's own catch-all collapses every parse failure into the
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

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.PARSE_FAILED);
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

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.PARSE_FAILED);
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
    writeOdtWithStyles(file, textBodyXml, null);
  }

  private static void writeOdtWithStyles(Path file, String textBodyXml, String masterPageStylesXml)
      throws IOException {
    String styles =
        masterPageStylesXml == null
            ? null
            : "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<office:document-styles"
                + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
                + " xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\""
                + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\">"
                + "<office:master-styles><style:master-page>"
                + masterPageStylesXml
                + "</style:master-page></office:master-styles>"
                + "</office:document-styles>";
    writeOdtWithRawStyles(file, textBodyXml, styles);
  }

  /**
   * Wraps one or more already-complete {@code <style:master-page>} elements into a full styles.xml,
   * for tests that need more than one master page (a single {@code writeOdtWithStyles} call only
   * ever produces one).
   */
  private static String wrapOdtMasterStyles(String masterPagesXml) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<office:document-styles"
        + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
        + " xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\""
        + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\">"
        + "<office:master-styles>"
        + masterPagesXml
        + "</office:master-styles>"
        + "</office:document-styles>";
  }

  private static void writeOdtWithRawStyles(Path file, String textBodyXml, String stylesXml)
      throws IOException {
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
      if (stylesXml != null) {
        out.putNextEntry(new ZipEntry("styles.xml"));
        out.write(stylesXml.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
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
