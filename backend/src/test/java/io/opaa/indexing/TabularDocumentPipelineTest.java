package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The XLSX/CSV/ODS pipeline (#1058, #1057; ingestion-pipelines.md Teil 3, Punkt 3): tabular
 * structure - not flattened prose - survives the cut, headers repeat in every chunk, and
 * blatt/tabelle show up as structural context.
 */
class TabularDocumentPipelineTest {

  @TempDir Path tempDir;

  private final TabularDocumentPipeline pipeline =
      new TabularDocumentPipeline(new TabularProperties(0, 0, 0, 0));

  @Test
  void claimsExactlyXlsxCsvAndOds() {
    assertThat(pipeline.handledFormats()).containsExactlyInAnyOrder(".xlsx", ".csv", ".ods");
    assertThat(pipeline.id()).isEqualTo("tabular");
    assertThat(pipeline.version()).isEqualTo((short) 1);
  }

  // --- XLSX ------------------------------------------------------------------------------------

  @Test
  void aSingleSheetProducesOneChunkWithRepeatedHeaderAndSheetContext() throws IOException {
    Path file = tempDir.resolve("gebuehren.xlsx");
    writeWorkbook(
        file,
        sheet("Gebühren", List.of("Leistung", "Betrag"), List.of("Personalausweis", "37,00 EUR")));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, file.getFileName().toString()));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    String text = result.chunks().getFirst().getText();
    assertThat(text)
        .contains("Blatt: Gebühren")
        .contains("Tabelle: Gebühren")
        .contains("Leistung | Betrag")
        .contains("Personalausweis | 37,00 EUR");
    assertThat(result.chunks().getFirst().getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Blatt Gebühren · Zeile 2");
  }

  @Test
  void multipleSheetsEachProduceTheirOwnChunksWithTheirOwnSheetName() throws IOException {
    Path file = tempDir.resolve("verzeichnis.xlsx");
    writeWorkbook(
        file,
        sheet("Gebühren", List.of("Leistung", "Betrag"), List.of("Ausweis", "37,00 EUR")),
        sheet("Zuständigkeiten", List.of("Name", "Amt"), List.of("Müller", "Bauamt")));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "verzeichnis.xlsx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().get(0).getText()).contains("Blatt: Gebühren");
    assertThat(result.chunks().get(1).getText()).contains("Blatt: Zuständigkeiten");
  }

  @Test
  void anEmptySheetAmongOthersContributesNoChunkOfItsOwn() throws IOException {
    Path file = tempDir.resolve("gemischt.xlsx");
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      workbook.createSheet("Leer");
      Sheet data = workbook.createSheet("Daten");
      writeRow(data, 0, "Spalte");
      writeRow(data, 1, "Wert");
      try (var out = Files.newOutputStream(file)) {
        workbook.write(out);
      }
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "gemischt.xlsx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).contains("Blatt: Daten");
  }

  @Test
  void aWorkbookWhoseOnlySheetIsEmptyHasNoExtractableText() throws IOException {
    Path file = tempDir.resolve("leer.xlsx");
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      workbook.createSheet("Leer");
      try (var out = Files.newOutputStream(file)) {
        workbook.write(out);
      }
    }

    DocumentPipelineResult result = pipeline.run(DocumentPipelineSource.ofFile(file, "leer.xlsx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void aSoleRowWithMultipleColumnsIsIndexedAsItsOwnChunkNotDiscardedAsAnEmptyHeader()
      throws IOException {
    // #1096 review, finding 9: a sheet that never had more than one row has no header/data split
    // to make - the sole row is Nutzdaten (e.g. a one-line summary table), not an empty header,
    // and must not be discarded.
    Path file = tempDir.resolve("eine-zeile.xlsx");
    writeWorkbook(file, sheetHeaderOnly("Blatt1", List.of("Name", "Amt")));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "eine-zeile.xlsx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).contains("Name | Amt");
  }

  @Test
  void manyRowsAreSplitAcrossChunksOfAtMostFiftyRowsWithTheHeaderRepeated() throws IOException {
    Path file = tempDir.resolve("liste.xlsx");
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Liste");
      writeRow(sheet, 0, "Nr", "Name");
      for (int i = 1; i <= 120; i++) {
        writeRow(sheet, i, String.valueOf(i), "Eintrag " + i);
      }
      try (var out = Files.newOutputStream(file)) {
        workbook.write(out);
      }
    }

    DocumentPipelineResult result = pipeline.run(DocumentPipelineSource.ofFile(file, "liste.xlsx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    // 120 data rows at <=50 rows per chunk: 3 chunks (50/50/20).
    assertThat(result.chunks()).hasSize(3);
    assertThat(result.chunks()).allMatch(c -> c.getText().contains("Nr | Name"));
    assertThat(result.chunks().getFirst().getText())
        .contains("Eintrag 1")
        .doesNotContain("Eintrag 51");
    assertThat(result.chunks().get(1).getText())
        .contains("Eintrag 51")
        .doesNotContain("Eintrag 101");
    assertThat(result.chunks().get(2).getText()).contains("Eintrag 101").contains("Eintrag 120");
  }

  @Test
  void aFormulaCellRendersItsCachedValueNotTheFormulaText() throws IOException {
    // #1096 review, finding 4: without setUseCachedValuesForFormulaCells, DataFormatter renders
    // the formula text itself ("100+1140") rather than the value a spreadsheet application shows.
    Path file = tempDir.resolve("formel.xlsx");
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Haushalt");
      writeRow(sheet, 0, "Posten", "Betrag");
      Row dataRow = sheet.createRow(1);
      dataRow.createCell(0).setCellValue("Straßenbau");
      Cell formulaCell = dataRow.createCell(1);
      formulaCell.setCellFormula("100+1140");
      workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formulaCell);
      try (var out = Files.newOutputStream(file)) {
        workbook.write(out);
      }
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "formel.xlsx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks().getFirst().getText()).contains("1240").doesNotContain("100+1140");
  }

  @Test
  void xlsxRowsWiderThanTheConfiguredLimitAreTruncatedNotCrashed() throws IOException {
    // #1096 review, finding 10: the column-width cap applies symmetrically to XLSX, not just ODS.
    TabularDocumentPipeline narrowPipeline =
        new TabularDocumentPipeline(new TabularProperties(3, 0, 0, 0));
    Path file = tempDir.resolve("breit.xlsx");
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Breit");
      writeRow(sheet, 0, "A", "B", "C", "D", "E");
      writeRow(sheet, 1, "1", "2", "3", "4", "5");
      try (var out = Files.newOutputStream(file)) {
        workbook.write(out);
      }
    }

    DocumentPipelineResult result =
        narrowPipeline.run(DocumentPipelineSource.ofFile(file, "breit.xlsx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    String text = result.chunks().getFirst().getText();
    assertThat(text).contains("A | B | C").doesNotContain("D").doesNotContain("E");
  }

  @Test
  void aGenuineXlsxMisnamedCsvIsRoutedByDetectedContentNotByItsName() throws IOException {
    // #1096 review, finding 3: the pipeline dispatches on the detected extension the registry
    // resolved, not on the (possibly misleading) file name - a real XLSX renamed .csv must still
    // be read as XLSX rather than fed to the CSV parser, which would fail on binary content.
    Path file = tempDir.resolve("bericht.csv");
    writeWorkbook(
        file, sheet("Bericht", List.of("Posten", "Betrag"), List.of("Straßenbau", "1240")));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "bericht.csv", ".xlsx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks().getFirst().getText()).contains("Blatt: Bericht");
  }

  @Test
  void anOdsMisnamedXlsxIsRoutedByDetectedContentNotByItsName() throws IOException {
    // #1096 review, finding 3, the reverse direction: an ODS renamed .xlsx must not be handed to
    // POI, which cannot open it at all.
    Path file = tempDir.resolve("bericht.xlsx");
    writeOds(file, odsTable("Bericht", odsRow("Posten", "Betrag"), odsRow("Straßenbau", "1240")));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "bericht.xlsx", ".ods"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks().getFirst().getText()).contains("Blatt: Bericht");
  }

  private static void writeRow(Sheet sheet, int rowIndex, String... values) {
    Row row = sheet.createRow(rowIndex);
    for (int i = 0; i < values.length; i++) {
      row.createCell(i).setCellValue(values[i]);
    }
  }

  private record SheetFixture(String name, List<String> header, List<String> row) {}

  private static SheetFixture sheet(String name, List<String> header, List<String> row) {
    return new SheetFixture(name, header, row);
  }

  private static SheetFixture sheetHeaderOnly(String name, List<String> header) {
    return new SheetFixture(name, header, null);
  }

  private static void writeWorkbook(Path file, SheetFixture... sheets) throws IOException {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      for (SheetFixture fixture : sheets) {
        Sheet sheet = workbook.createSheet(fixture.name());
        writeRow(sheet, 0, fixture.header().toArray(new String[0]));
        if (fixture.row() != null) {
          writeRow(sheet, 1, fixture.row().toArray(new String[0]));
        }
      }
      try (var out = Files.newOutputStream(file)) {
        workbook.write(out);
      }
    }
  }

  // --- CSV -------------------------------------------------------------------------------------

  @Test
  void aCommaDelimitedCsvProducesOneChunkWithRepeatedHeaderAndTableContext() throws IOException {
    Path file = tempDir.resolve("gebuehren.csv");
    Files.writeString(file, "Leistung,Betrag\nPersonalausweis,37 EUR\n", StandardCharsets.UTF_8);

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "gebuehren.csv"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText())
        .contains("Tabelle: gebuehren")
        .contains("Leistung | Betrag")
        .contains("Personalausweis | 37 EUR")
        .doesNotContain("Blatt:");
  }

  @Test
  void aSemicolonDelimitedCsvIsDetectedAndParsedCorrectly() throws IOException {
    Path file = tempDir.resolve("zustaendigkeiten.csv");
    Files.writeString(
        file, "Name;Amt\nMüller;Bauamt\nSchmidt;Ordnungsamt\n", StandardCharsets.UTF_8);

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "zustaendigkeiten.csv"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    String text = result.chunks().getFirst().getText();
    assertThat(text)
        .contains("Name | Amt")
        .contains("Müller | Bauamt")
        .contains("Schmidt | Ordnungsamt");
  }

  @Test
  void aTabDelimitedCsvIsDetectedAndParsedCorrectly() throws IOException {
    Path file = tempDir.resolve("haushalt.csv");
    Files.writeString(file, "Posten\tBetrag\nStraßenbau\t120000\n", StandardCharsets.UTF_8);

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "haushalt.csv"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks().getFirst().getText())
        .contains("Posten | Betrag")
        .contains("Straßenbau | 120000");
  }

  @Test
  void aWindowsCp1252EncodedCsvIsDecodedCorrectly() throws IOException {
    // #1096 review, finding 1: German Excel's own default CSV export encoding - not valid UTF-8
    // for any text containing an umlaut or the Euro sign.
    Path file = tempDir.resolve("strassen.csv");
    byte[] bytes = "Name;Straße\nMüller;Königsallee\n".getBytes(Charset.forName("windows-1252"));
    Files.write(file, bytes);

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "strassen.csv"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks().getFirst().getText())
        .contains("Name | Straße")
        .contains("Müller | Königsallee");
  }

  @Test
  void aUtf8CsvWithALeadingByteOrderMarkStripsIt() throws IOException {
    Path file = tempDir.resolve("bom.csv");
    byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    byte[] content = "Name,Amt\nMüller,Bauamt\n".getBytes(StandardCharsets.UTF_8);
    byte[] withBom = new byte[bom.length + content.length];
    System.arraycopy(bom, 0, withBom, 0, bom.length);
    System.arraycopy(content, 0, withBom, bom.length, content.length);
    Files.write(file, withBom);

    DocumentPipelineResult result = pipeline.run(DocumentPipelineSource.ofFile(file, "bom.csv"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    String text = result.chunks().getFirst().getText();
    assertThat(text).contains("Name | Amt").doesNotContain("﻿");
    // The BOM must not survive as a stray character glued to the first header cell.
    assertThat(text).doesNotContain("﻿Name");
  }

  @Test
  void aQuotedFieldContainingAnotherDelimiterDoesNotConfuseDetection() throws IOException {
    // #1096 review, finding 2: raw character counting used to tie (one comma, one semicolon) and
    // always resolve ties to comma - splitting the quoted field and failing the row. The real
    // delimiter (semicolon) must win because it is the one that actually produces columns once
    // quoting is respected.
    Path file = tempDir.resolve("gequotet.csv");
    Files.writeString(
        file, "\"Leistung, allgemein\";Betrag\n\"Ausstellung\";37 EUR\n", StandardCharsets.UTF_8);

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "gequotet.csv"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks().getFirst().getText())
        .contains("Leistung, allgemein | Betrag")
        .contains("Ausstellung | 37 EUR");
  }

  @Test
  void anEmptyCsvHasNoExtractableText() throws IOException {
    Path file = tempDir.resolve("leer.csv");
    Files.writeString(file, "", StandardCharsets.UTF_8);

    DocumentPipelineResult result = pipeline.run(DocumentPipelineSource.ofFile(file, "leer.csv"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
  }

  @Test
  void aSoleCsvRowWithMultipleColumnsIsIndexedAsItsOwnChunkNotDiscardedAsAnEmptyHeader()
      throws IOException {
    Path file = tempDir.resolve("eine-zeile.csv");
    Files.writeString(file, "Name,Amt\n", StandardCharsets.UTF_8);

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "eine-zeile.csv"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).contains("Name | Amt");
  }

  @Test
  void manyCsvRowsAreSplitAcrossChunksOfAtMostFiftyRowsWithTheHeaderRepeated() throws IOException {
    StringBuilder csv = new StringBuilder("Nr,Name\n");
    for (int i = 1; i <= 120; i++) {
      csv.append(i).append(",Eintrag ").append(i).append('\n');
    }
    Path file = tempDir.resolve("liste.csv");
    Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);

    DocumentPipelineResult result = pipeline.run(DocumentPipelineSource.ofFile(file, "liste.csv"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(3);
    assertThat(result.chunks()).allMatch(c -> c.getText().contains("Nr | Name"));
  }

  @Test
  void aSingleGiantRowBecomesItsOwnChunkInsteadOfBeingSplitOrDropped() throws IOException {
    String hugeValue = "x".repeat(10_000);
    Path file = tempDir.resolve("riesenzeile.csv");
    Files.writeString(
        file, "Spalte1,Spalte2\n" + hugeValue + ",normal\nzweite,zeile\n", StandardCharsets.UTF_8);

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "riesenzeile.csv"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    // The giant row alone exceeds the char budget and gets its own chunk; the normal row that
    // follows starts a fresh one rather than being appended to an already-oversized chunk.
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().getFirst().getText()).contains(hugeValue);
    assertThat(result.chunks().get(1).getText()).contains("zweite | zeile");
  }

  @Test
  void aRowExceedingTheHardCharacterCeilingIsTruncatedWithAVisibleMarker() throws IOException {
    // #1096 review, finding 12: MAX_CHUNK_CHARS alone does not bound a single, giant row - without
    // a hard ceiling it would be handed to the embedding model unbounded and fail there instead.
    String hugeValue = "x".repeat(30_000);
    Path file = tempDir.resolve("riesig.csv");
    Files.writeString(file, "Spalte1,Spalte2\n" + hugeValue + ",normal\n", StandardCharsets.UTF_8);

    DocumentPipelineResult result = pipeline.run(DocumentPipelineSource.ofFile(file, "riesig.csv"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    String text = result.chunks().getFirst().getText();
    assertThat(text.length()).isLessThanOrEqualTo(TabularDocumentPipeline.HARD_CHUNK_CHAR_LIMIT);
    assertThat(text).endsWith("[…gekürzt]");
  }

  @Test
  void extractedTextWithoutAFileIsParsedAsCsvToo() {
    DocumentPipelineResult result =
        pipeline.run(
            DocumentPipelineSource.ofExtractedText("Name,Amt\nMüller,Bauamt\n", "export.csv"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks().getFirst().getText()).contains("Müller | Bauamt");
  }

  // --- ODS (#1057 admits .ods; POI does not read OpenDocument, see readOds) --------------------

  @Test
  void aSingleOdsSheetProducesOneChunkWithRepeatedHeaderAndSheetContext() throws IOException {
    Path file = tempDir.resolve("gebuehren.ods");
    writeOds(
        file,
        odsTable("Gebühren", odsRow("Leistung", "Betrag"), odsRow("Personalausweis", "37,00 EUR")));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "gebuehren.ods"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText())
        .contains("Blatt: Gebühren")
        .contains("Tabelle: Gebühren")
        .contains("Leistung | Betrag")
        .contains("Personalausweis | 37,00 EUR");
    assertThat(result.chunks().getFirst().getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Blatt Gebühren · Zeile 2");
  }

  @Test
  void multipleOdsSheetsEachProduceTheirOwnChunksWithTheirOwnSheetName() throws IOException {
    Path file = tempDir.resolve("verzeichnis.ods");
    writeOds(
        file,
        odsTable("Gebühren", odsRow("Leistung", "Betrag"), odsRow("Ausweis", "37,00 EUR"))
            + odsTable("Zuständigkeiten", odsRow("Name", "Amt"), odsRow("Müller", "Bauamt")));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "verzeichnis.ods"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().get(0).getText()).contains("Blatt: Gebühren");
    assertThat(result.chunks().get(1).getText()).contains("Blatt: Zuständigkeiten");
  }

  @Test
  void anEmptyOdsSheetAmongOthersContributesNoChunkOfItsOwn() throws IOException {
    Path file = tempDir.resolve("gemischt.ods");
    writeOds(file, odsTable("Leer") + odsTable("Daten", odsRow("Spalte"), odsRow("Wert")));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "gemischt.ods"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).contains("Blatt: Daten");
  }

  @Test
  void anOdsSpreadsheetWhoseOnlySheetIsEmptyHasNoExtractableText() throws IOException {
    Path file = tempDir.resolve("leer.ods");
    writeOds(file, odsTable("Leer"));

    DocumentPipelineResult result = pipeline.run(DocumentPipelineSource.ofFile(file, "leer.ods"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
  }

  @Test
  void aSoleOdsRowWithMultipleColumnsIsIndexedAsItsOwnChunkNotDiscardedAsAnEmptyHeader()
      throws IOException {
    Path file = tempDir.resolve("eine-zeile.ods");
    writeOds(file, odsTable("Blatt1", odsRow("Name", "Amt")));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "eine-zeile.ods"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).contains("Name | Amt");
  }

  @Test
  void aCoveredTableCellFromAMergedRegionIsTreatedAsBlankRatherThanCrashing() throws IOException {
    Path file = tempDir.resolve("verbunden.ods");
    writeOds(
        file,
        odsTable(
            "Blatt1",
            odsRow("Name", "Amt"),
            "<table:table-row>"
                + "<table:table-cell office:value-type=\"string\"><text:p>Müller</text:p>"
                + "</table:table-cell>"
                + "<table:covered-table-cell/>"
                + "</table:table-row>"));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "verbunden.ods"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks().getFirst().getText()).contains("Müller |");
  }

  @Test
  void aHugeTrailingColumnRepeatIsCappedRatherThanBallooningTheChunk() throws IOException {
    // ODF exporters routinely pad a row to the sheet's full width (e.g. 16384) with a single
    // repeated blank cell - the "Riesenzeile" guard for ODS, see MAX_CELL_REPEAT/MAX_ROW_COLUMNS.
    Path file = tempDir.resolve("riesenspalte.ods");
    String headerRowWithTrailingRepeat =
        "<table:table-row>"
            + "<table:table-cell office:value-type=\"string\"><text:p>Name</text:p></table:table-cell>"
            + "<table:table-cell office:value-type=\"string\"><text:p>Amt</text:p></table:table-cell>"
            + "<table:table-cell table:number-columns-repeated=\"16384\"/>"
            + "</table:table-row>";
    writeOds(file, odsTable("Blatt1", headerRowWithTrailingRepeat, odsRow("Müller", "Bauamt")));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "riesenspalte.ods"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    // Capped at 200 columns per row rather than 16384 - a bounded, not unboundedly large, chunk.
    assertThat(result.chunks().getFirst().getText()).hasSizeLessThan(5_000);
  }

  @Test
  void anOdsContentXmlWithADoctypeIsRejectedRatherThanResolvingExternalEntities()
      throws IOException {
    // XXE hardening: content.xml comes from an uploaded/indexed file, never trusted input.
    Path file = tempDir.resolve("xxe.ods");
    String maliciousContent =
        "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE office:document-content [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + "<office:document-content"
            + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
            + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\""
            + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\">"
            + "<office:body><office:spreadsheet>"
            + odsTable("Blatt1", odsRow("Name", "&xxe;"))
            + "</office:spreadsheet></office:body></office:document-content>";
    try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
      out.putNextEntry(new ZipEntry("content.xml"));
      out.write(maliciousContent.getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }

    assertThatThrownBy(() -> pipeline.run(DocumentPipelineSource.ofFile(file, "xxe.ods")))
        .isInstanceOf(UncheckedIOException.class);
  }

  @Test
  void odsRowNumbersAdvanceByTheFullRepeatSpanOfARepeatedBlankRow() throws IOException {
    // #1096 review, finding 11: table:number-rows-repeated must advance the running row counter
    // by the full repeat span, not by one, so a citation's "Zeile n" is correct for every row
    // after a filler gap.
    Path file = tempDir.resolve("zeilennummern.ods");
    String repeatedBlankRow =
        "<table:table-row table:number-rows-repeated=\"5\"><table:table-cell/></table:table-row>";
    writeOds(
        file,
        odsTable(
            "Blatt1",
            odsRow("Name", "Amt"),
            odsRow("Müller", "Bauamt"),
            repeatedBlankRow,
            odsRow("Schmidt", "Ordnungsamt")));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "zeilennummern.ods"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    // Header at row 1, "Müller" at row 2, five blank filler rows (3-7), "Schmidt" at row 8.
    assertThat(result.chunks().getFirst().getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Blatt Blatt1 · Zeilen 2–8");
  }

  @Test
  void anOdsContentXmlExceedingTheByteLimitIsRejectedRatherThanExhaustingMemory()
      throws IOException {
    // #1096 review, finding 6: the zip-bomb guard on content.xml's decompressed byte stream.
    TabularDocumentPipeline tinyLimitPipeline =
        new TabularDocumentPipeline(new TabularProperties(0, 0, 50, 0));
    Path file = tempDir.resolve("gross.ods");
    writeOds(file, odsTable("Blatt1", odsRow("Name", "Amt"), odsRow("Müller", "Bauamt")));

    assertThatThrownBy(
            () -> tinyLimitPipeline.run(DocumentPipelineSource.ofFile(file, "gross.ods")))
        .isInstanceOf(UncheckedIOException.class);
  }

  @Test
  void anOdsSpreadsheetExceedingTheRowLimitIsRejectedRatherThanExhaustingMemory()
      throws IOException {
    // #1096 review, finding 6: the second, row-count-based zip-bomb guard - a small, repetitive
    // content.xml could stay under the byte limit while still describing too many rows.
    TabularDocumentPipeline tinyLimitPipeline =
        new TabularDocumentPipeline(new TabularProperties(0, 0, 0, 2));
    Path file = tempDir.resolve("viele-zeilen.ods");
    writeOds(
        file,
        odsTable(
            "Blatt1", odsRow("Name", "Amt"), odsRow("A", "1"), odsRow("B", "2"), odsRow("C", "3")));

    assertThatThrownBy(
            () -> tinyLimitPipeline.run(DocumentPipelineSource.ofFile(file, "viele-zeilen.ods")))
        .isInstanceOf(UncheckedIOException.class);
  }

  private static void writeOds(Path file, String spreadsheetBodyXml) throws IOException {
    String content =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<office:document-content"
            + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
            + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\""
            + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\">"
            + "<office:body><office:spreadsheet>"
            + spreadsheetBodyXml
            + "</office:spreadsheet></office:body></office:document-content>";
    try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
      out.putNextEntry(new ZipEntry("content.xml"));
      out.write(content.getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
  }

  private static String odsTable(String name, String... rows) {
    StringBuilder xml = new StringBuilder("<table:table table:name=\"").append(name).append("\">");
    for (String row : rows) {
      xml.append(row);
    }
    return xml.append("</table:table>").toString();
  }

  private static String odsRow(String... cellValues) {
    StringBuilder xml = new StringBuilder("<table:table-row>");
    for (String value : cellValues) {
      xml.append("<table:table-cell office:value-type=\"string\"><text:p>")
          .append(value)
          .append("</text:p></table:table-cell>");
    }
    return xml.append("</table:table-row>").toString();
  }
}
