package io.opaa.indexing.format.file.office;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.indexing.chunk.ChunkingService;
import io.opaa.indexing.format.DocumentFormatResult;
import io.opaa.indexing.format.DocumentFormatRunner;
import io.opaa.indexing.format.DocumentFormatSource;
import io.opaa.indexing.format.PassthroughMetadataKeysTestSupport;
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
 * The ODP pipeline (ingestion-pipelines.md Teil 3 Punkt 2: "eine Folie = ein Chunk"): mirrors
 * PptxDocumentFormatTest for the per-slide chunking rules, plus the ODS-style XXE/zip-bomb guards
 * {@code TabularDocumentFormatTest} exercises for its own {@code content.xml} reader.
 */
class OdpDocumentFormatTest {

  @TempDir Path tempDir;

  private final OdpDocumentFormat pipeline =
      new OdpDocumentFormat(new OdfProperties(0, 0, 0, 0, 0));

  private static final String META_XML =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<office:document-meta"
          + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
          + " xmlns:meta=\"urn:oasis:names:tc:opendocument:xmlns:meta:1.0\""
          + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
          + "<office:meta><dc:title>Haushaltsplan 2026</dc:title>"
          + "<dc:date>2026-03-12T09:15:00</dc:date></office:meta>"
          + "</office:document-meta>";

  /**
   * One ingest opens the ZIP package once; meta.xml, content.xml and styles.xml all come out of
   * that one opening - the ODF counterpart of FileDocumentFormatTest's read counter.
   */
  @Test
  void oneRunOpensThePackageOnceAndReadsMetaContentAndStylesFromIt() throws IOException {
    CountingOdfOpener opener = new CountingOdfOpener();
    OdpDocumentFormat counting = new OdpDocumentFormat(new OdfProperties(0, 0, 0, 0, 0), opener);
    Path file = tempDir.resolve("einmal.odp");
    writeOdpWithRawStyles(
        file,
        odpSlide(odpFrame("title", "Einfuehrung") + odpFrame(null, "Willkommen.")),
        wrapOdpMasterStyles(
            "<style:master-page><draw:frame><draw:text-box><text:p>Stadt Musterstadt</text:p>"
                + "</draw:text-box></draw:frame></style:master-page>"),
        META_XML);

    DocumentFormatResult result =
        counting.run(DocumentFormatSource.ofFile(file, "einmal.odp", ".odp"));

    assertThat(opener.opens).hasValue(1);
    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().getFirst().getText()).contains("Stadt Musterstadt");
    assertThat(result.chunks().get(1).getText()).startsWith("Einfuehrung");
    assertThat(result.properties().title()).isEqualTo("Haushaltsplan 2026");
  }

  @Test
  void readingPropertiesAloneOpensThePackageOnceForMeta() throws IOException {
    CountingOdfOpener opener = new CountingOdfOpener();
    OdpDocumentFormat counting = new OdpDocumentFormat(new OdfProperties(0, 0, 0, 0, 0), opener);
    Path file = tempDir.resolve("einmal-eigenschaften.odp");
    writeOdpWithRawStyles(file, odpSlide(odpFrame("title", "Einfuehrung")), null, META_XML);

    io.opaa.indexing.format.DocumentProperties properties =
        counting.readProperties(
            DocumentFormatSource.ofFile(file, "einmal-eigenschaften.odp", ".odp"));

    assertThat(opener.opens).hasValue(1);
    assertThat(properties.title()).isEqualTo("Haushaltsplan 2026");
  }

  @Test
  void claimsExactlyOdp() {
    assertThat(pipeline.handledFormats()).containsExactly(".odp");
    assertThat(pipeline.id()).isEqualTo("odp");
    assertThat(pipeline.version()).isEqualTo((short) 2);
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

    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofFile(file, "praesentation.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
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
  void aBlankSlideAlongsideOthersStillBecomesItsOwnChunk() throws IOException {
    Path file = tempDir.resolve("mit-leerer-folie.odp");
    writeOdp(
        file,
        odpSlide(odpFrame("title", "Einfuehrung") + odpFrame(null, "Willkommen.")) + odpSlide(""));

    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofFile(file, "mit-leerer-folie.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().get(1).getText()).isEqualTo("Folie 2");
  }

  @Test
  void masterSlideTextFromStylesXmlBecomesOneDeduplicatedLeadingChunk() throws IOException {
    // regression guard for #1145: an authority name/Aktenzeichen placed exclusively on the
    // Masterfolie must still be lexically searchable, and only once - not per slide and not
    // dropped, as it would be without reading styles.xml at all.
    Path file = tempDir.resolve("mit-masterfolie.odp");
    writeOdpWithStyles(
        file,
        odpSlide(odpFrame("title", "Einfuehrung") + odpFrame(null, "Willkommen.")),
        "<draw:frame><draw:text-box><text:p>Stadt Musterstadt · Az. 12-34/2026</text:p>"
            + "</draw:text-box></draw:frame>");

    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofFile(file, "mit-masterfolie.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().getFirst().getText()).contains("Stadt Musterstadt · Az. 12-34/2026");
    assertThat(result.chunks().getFirst().getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Masterfolie");
    assertThat(result.chunks().get(1).getText()).startsWith("Einfuehrung");
  }

  @Test
  void masterSlideTextAloneDoesNotDefeatTheScanEmptyDeckGuard() throws IOException {
    // regression guard for #1145: a guard reading "(no slide chunks or no slide has text) AND no
    // master-slide chunk" would report a scan-only presentation whose master slide carries a
    // Behoerdenname - the normal case for any authority template - as CHUNKED instead of
    // NO_EXTRACTABLE_TEXT, reopening the stille-Leer-Index-Fehlfunktion this guard prevents.
    Path file = tempDir.resolve("nur-masterfolie.odp");
    writeOdpWithStyles(
        file,
        odpSlide("") + odpSlide(""),
        "<draw:frame><draw:text-box><text:p>Stadt Musterstadt</text:p></draw:text-box>"
            + "</draw:frame>");

    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofFile(file, "nur-masterfolie.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.NO_EXTRACTABLE_TEXT);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void multipleMasterPagesWithAnIdenticalTextContributeOnlyOnce() throws IOException {
    // regression guard for #1145.
    Path file = tempDir.resolve("mehrere-masterfolien.odp");
    String masterPagesXml =
        "<style:master-page style:name=\"Standard\">"
            + "<draw:frame><draw:text-box><text:p>Stadt Musterstadt</text:p></draw:text-box>"
            + "</draw:frame></style:master-page>"
            + "<style:master-page style:name=\"Titel\">"
            + "<draw:frame><draw:text-box><text:p>Stadt Musterstadt</text:p></draw:text-box>"
            + "</draw:frame></style:master-page>";
    writeOdpWithRawStyles(
        file,
        odpSlide(odpFrame("title", "Einfuehrung") + odpFrame(null, "Willkommen.")),
        wrapOdpMasterStyles(masterPagesXml));

    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofFile(file, "mehrere-masterfolien.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    long occurrences =
        result.chunks().getFirst().getText().split("Stadt Musterstadt", -1).length - 1;
    assertThat(occurrences).isEqualTo(1);
  }

  @Test
  void aNonBreakingSpaceVariantIsDeduplicatedAgainstThePlainSpaceVariant() throws IOException {
    // regression guard for #1145: a non-breaking space (U+00A0) is routine in
    // an authority letterhead's column separators; \s alone does not match it, so a master page
    // using NBSP and another using a plain space would otherwise both survive deduplication.
    Path file = tempDir.resolve("geschuetztes-leerzeichen.odp");
    String masterPagesXml =
        "<style:master-page style:name=\"Standard\">"
            + "<draw:frame><draw:text-box><text:p>Stadt Musterstadt</text:p></draw:text-box>"
            + "</draw:frame></style:master-page>"
            + "<style:master-page style:name=\"Titel\">"
            + "<draw:frame><draw:text-box><text:p>Stadt\u00A0Musterstadt</text:p></draw:text-box>"
            + "</draw:frame></style:master-page>";
    writeOdpWithRawStyles(
        file,
        odpSlide(odpFrame("title", "Einfuehrung") + odpFrame(null, "Willkommen.")),
        wrapOdpMasterStyles(masterPagesXml));

    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofFile(file, "geschuetztes-leerzeichen.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    long occurrences = result.chunks().getFirst().getText().split("Stadt", -1).length - 1;
    assertThat(occurrences).isEqualTo(1);
  }

  @Test
  void aPageNumberFieldOnTheMasterSlideIsExcludedButSurroundingTextIsKept() throws IOException {
    // regression guard for #1145.
    Path file = tempDir.resolve("seitenzahl-masterfolie.odp");
    writeOdpWithStyles(
        file,
        odpSlide(odpFrame("title", "Einfuehrung") + odpFrame(null, "Willkommen.")),
        "<draw:frame><draw:text-box><text:p>Seite "
            + "<text:page-number>1</text:page-number></text:p></draw:text-box></draw:frame>");

    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofFile(file, "seitenzahl-masterfolie.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().getFirst().getText()).isEqualTo("Seite");
  }

  @Test
  void aNonContentPlaceholderClassOnTheMasterSlideIsExcluded() throws IOException {
    // regression guard for #1145: OdpStylesHandler must apply the same
    // NON_CONTENT_PLACEHOLDER_CLASSES filter OdpContentHandler already applies to notes, or an
    // outline scaffolding prompt ("Mastertextformat bearbeiten") ends up indexed as if it were
    // authored content.
    Path file = tempDir.resolve("platzhalter-masterfolie.odp");
    writeOdpWithStyles(
        file,
        odpSlide(odpFrame("title", "Einfuehrung") + odpFrame(null, "Willkommen.")),
        "<draw:frame presentation:class=\"footer\"><draw:text-box>"
            + "<text:p>Seite 1 von 3</text:p></draw:text-box></draw:frame>"
            + "<draw:frame><draw:text-box><text:p>Stadt Musterstadt</text:p></draw:text-box>"
            + "</draw:frame>");

    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofFile(file, "platzhalter-masterfolie.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().getFirst().getText())
        .isEqualTo("Stadt Musterstadt")
        .doesNotContain("Seite 1 von 3");
  }

  @Test
  void aStylesXmlWithADoctypeOnlyForfeitsTheMasterSlideChunkNotTheWholePresentation()
      throws IOException {
    // regression guard for #1145.
    Path file = tempDir.resolve("xxe-styles.odp");
    String maliciousStyles =
        "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE office:document-styles [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + "<office:document-styles"
            + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
            + " xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\""
            + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
            + " xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\">"
            + "<office:master-styles><style:master-page>"
            + "<draw:frame><draw:text-box><text:p>&xxe;</text:p></draw:text-box></draw:frame>"
            + "</style:master-page></office:master-styles>"
            + "</office:document-styles>";
    writeOdpWithRawStyles(
        file,
        odpSlide(odpFrame("title", "Einfuehrung") + odpFrame(null, "Willkommen.")),
        maliciousStyles);

    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofFile(file, "xxe-styles.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).startsWith("Einfuehrung");
  }

  @Test
  void aPresentationWhereNoSlideHasAnyTextIsRejectedAsNoExtractableText() throws IOException {
    Path file = tempDir.resolve("nur-bilder.odp");
    writeOdp(file, odpSlide("") + odpSlide(""));

    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofFile(file, "nur-bilder.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.NO_EXTRACTABLE_TEXT);
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

    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofFile(file, "tabelle.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
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

    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofFile(file, "ausrichtung.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
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

    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofFile(file, "verschachtelte-tabelle.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText())
        .contains("Leistung | Gebuehr")
        .contains(" | Randnotiz")
        .contains("Personalausweis | 37,00 EUR")
        .doesNotContain("innen");
  }

  @Test
  void aNonContentNotesPlaceholderIsExcludedFromTheNotesText() throws IOException {
    // Mirrors PptxDocumentFormat's NON_CONTENT_NOTES_PLACEHOLDERS: layout scaffolding
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

    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofFile(file, "notiz-platzhalter.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(result.chunks().getFirst().getText())
        .contains("Notizen:")
        .contains("Echte Notiz.")
        .doesNotContain("Seite 1 von 3");
  }

  @Test
  void aPresentationWithoutAnySlideIsRejectedAsNoExtractableText() throws IOException {
    // A well-formed but empty <office:presentation/> is a parsed document with nothing to chunk,
    // not an unparseable one - distinct from aZipWithoutAContentXmlEntryIsAParseFailure below.
    // Same NO_EXTRACTABLE_TEXT outcome TikaFallbackFormat reports for this case.
    Path file = tempDir.resolve("keine-folien.odp");
    writeOdp(file, "");

    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofFile(file, "keine-folien.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.NO_EXTRACTABLE_TEXT);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void aZipWithoutAContentXmlEntryIsAParseFailure() throws IOException {
    Path file = tempDir.resolve("ohne-content-xml.odp");
    try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
      out.putNextEntry(new ZipEntry("mimetype"));
      out.write("application/vnd.oasis.opendocument.presentation".getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }

    DocumentFormatResult result =
        DocumentFormatRunner.run(
            pipeline, DocumentFormatSource.ofFile(file, "ohne-content-xml.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  @Test
  void aFilelessSourceIsAParseFailure() {
    // An ODP pipeline is only ever reached through a genuine .odp file (never RSS-extracted text,
    // ADR-0017 decision 2) - defensive fallback, mirrors DocxDocumentFormat/PptxDocumentFormat.
    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofExtractedText("irrelevanter Text", "quelle.odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void aFileThatIsNotAValidZipArchiveIsAParseFailure() throws IOException {
    Path file = tempDir.resolve("kaputt.odp");
    Files.writeString(file, "das ist kein odp");

    DocumentFormatResult result =
        DocumentFormatRunner.run(pipeline, DocumentFormatSource.ofFile(file, "kaputt.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
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

    DocumentFormatResult result =
        DocumentFormatRunner.run(pipeline, DocumentFormatSource.ofFile(file, "xxe.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  @Test
  void aContentXmlExceedingTheByteLimitIsRejectedRatherThanExhaustingMemory() throws IOException {
    OdpDocumentFormat tinyLimitPipeline = new OdpDocumentFormat(new OdfProperties(50, 0, 0, 0, 0));
    Path file = tempDir.resolve("gross.odp");
    writeOdp(file, odpSlide(odpFrame("title", "Titel") + odpFrame(null, "Ein laengerer Text.")));

    DocumentFormatResult result =
        DocumentFormatRunner.run(
            tinyLimitPipeline, DocumentFormatSource.ofFile(file, "gross.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  @Test
  void theByteLimitDirectlyThrowsAnIOExceptionNamingWhichLimitWasHit() throws IOException {
    // the pipeline's own catch-all collapses every parse failure into the
    // same NO_CONTENT outcome, so a wrong-reason failure would stay green there. This test goes
    // straight at OdfPackage.parse instead, the one place the byte limit's own message survives.
    Path file = tempDir.resolve("gross-direkt.odp");
    writeOdp(file, odpSlide(odpFrame("title", "Titel") + odpFrame(null, "Ein laengerer Text.")));

    assertThatThrownBy(() -> OdfPackage.parse(file, 50, new DefaultHandler()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("size limit");
  }

  @Test
  void aTextSWithAnExtremeRepeatCountIsCappedRatherThanExhaustingMemory() throws IOException {
    // regression guard for #1143: text:c is attacker-controlled and unrelated to content.xml's
    // byte size - without a cap, a single element requests gigabytes of in-memory spaces.
    OdpDocumentFormat tinyLimitPipeline = new OdpDocumentFormat(new OdfProperties(0, 0, 0, 5, 0));
    Path file = tempDir.resolve("weite-luecke.odp");
    writeOdp(file, odpSlide(odpFrame(null, "A<text:s text:c=\"2000000000\"/>B")));

    DocumentFormatResult result =
        tinyLimitPipeline.run(DocumentFormatSource.ofFile(file, "weite-luecke.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(result.chunks().getFirst().getText()).contains("A     B");
  }

  @Test
  void
      manyTextSElementsInOneParagraphAreRejectedByTheCumulativeCharacterBudgetRatherThanExhaustingMemory()
          throws IOException {
    // regression guard for #1143: the per-element cap (maxSpaceRepeat) bounds a single text:s
    // element, but text is only reset once per paragraph - an unbounded number of text:s elements
    // inside the same <text:p> would otherwise sum into the same buffer without limit.
    OdpDocumentFormat tinyLimitPipeline = new OdpDocumentFormat(new OdfProperties(0, 0, 0, 5, 12));
    Path file = tempDir.resolve("viele-leerzeichen.odp");
    writeOdp(
        file,
        odpSlide(
            odpFrame(
                null,
                "<text:s text:c=\"5\"/><text:s text:c=\"5\"/><text:s text:c=\"5\"/>"
                    + "<text:s text:c=\"5\"/>")));

    DocumentFormatResult result =
        DocumentFormatRunner.run(
            tinyLimitPipeline, DocumentFormatSource.ofFile(file, "viele-leerzeichen.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  @Test
  void theTextCharacterBudgetDirectlyThrowsASaxExceptionNamingWhichLimitWasHit()
      throws IOException {
    // See theByteLimitDirectlyThrowsAnIOExceptionNamingWhichLimitWasHit's own Javadoc.
    Path file = tempDir.resolve("viele-leerzeichen-direkt.odp");
    writeOdp(
        file,
        odpSlide(
            odpFrame(
                null,
                "<text:s text:c=\"5\"/><text:s text:c=\"5\"/><text:s text:c=\"5\"/>"
                    + "<text:s text:c=\"5\"/>")));
    OdpDocumentFormat.OdpContentHandler handler =
        new OdpDocumentFormat.OdpContentHandler(5_000, 5, 12);

    assertThatThrownBy(() -> OdfPackage.parse(file, 10_485_760L, handler))
        .isInstanceOf(IOException.class)
        .rootCause()
        .hasMessageContaining("text character limit");
  }

  @Test
  void aPresentationExceedingTheSlideLimitIsRejectedRatherThanExhaustingMemory()
      throws IOException {
    OdpDocumentFormat tinyLimitPipeline = new OdpDocumentFormat(new OdfProperties(0, 0, 1, 0, 0));
    Path file = tempDir.resolve("viele-folien.odp");
    writeOdp(
        file, odpSlide(odpFrame(null, "Folie eins.")) + odpSlide(odpFrame(null, "Folie zwei.")));

    DocumentFormatResult result =
        DocumentFormatRunner.run(
            tinyLimitPipeline, DocumentFormatSource.ofFile(file, "viele-folien.odp", ".odp"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  @Test
  void theSlideLimitDirectlyThrowsASaxExceptionNamingWhichLimitWasHit() throws IOException {
    // See theByteLimitDirectlyThrowsAnIOExceptionNamingWhichLimitWasHit's own Javadoc.
    Path file = tempDir.resolve("viele-folien-direkt.odp");
    writeOdp(
        file, odpSlide(odpFrame(null, "Folie eins.")) + odpSlide(odpFrame(null, "Folie zwei.")));
    OdpDocumentFormat.OdpContentHandler handler =
        new OdpDocumentFormat.OdpContentHandler(1, 1_000, 10_000_000L);

    assertThatThrownBy(() -> OdfPackage.parse(file, 10_485_760L, handler))
        .isInstanceOf(IOException.class)
        .rootCause()
        .hasMessageContaining("slide limit");
  }

  private static void writeOdp(Path file, String presentationBodyXml) throws IOException {
    writeOdpWithStyles(file, presentationBodyXml, null);
  }

  private static void writeOdpWithStyles(
      Path file, String presentationBodyXml, String masterPageXml) throws IOException {
    String styles =
        masterPageXml == null
            ? null
            : wrapOdpMasterStyles("<style:master-page>" + masterPageXml + "</style:master-page>");
    writeOdpWithRawStyles(file, presentationBodyXml, styles);
  }

  /**
   * Wraps one or more already-complete {@code <style:master-page>} elements into a full styles.xml,
   * for tests that need more than one master page (a single {@code writeOdpWithStyles} call only
   * ever produces one).
   */
  private static String wrapOdpMasterStyles(String masterPagesXml) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<office:document-styles"
        + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
        + " xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\""
        + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
        + " xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\">"
        + "<office:master-styles>"
        + masterPagesXml
        + "</office:master-styles>"
        + "</office:document-styles>";
  }

  private static void writeOdpWithRawStyles(Path file, String presentationBodyXml, String stylesXml)
      throws IOException {
    writeOdpWithRawStyles(file, presentationBodyXml, stylesXml, null);
  }

  private static void writeOdpWithRawStyles(
      Path file, String presentationBodyXml, String stylesXml, String metaXml) throws IOException {
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
      if (stylesXml != null) {
        out.putNextEntry(new ZipEntry("styles.xml"));
        out.write(stylesXml.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
      if (metaXml != null) {
        out.putNextEntry(new ZipEntry("meta.xml"));
        out.write(metaXml.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
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
