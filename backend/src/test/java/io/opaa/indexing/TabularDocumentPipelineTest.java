package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The XLSX/CSV pipeline (#1058, ingestion-pipelines.md Teil 3, Punkt 3): tabular structure - not
 * flattened prose - survives the cut, headers repeat in every chunk, and blatt/tabelle show up as
 * structural context.
 */
class TabularDocumentPipelineTest {

  @TempDir Path tempDir;

  private final TabularDocumentPipeline pipeline = new TabularDocumentPipeline();

  @Test
  void claimsExactlyXlsxAndCsv() {
    assertThat(pipeline.handledFormats()).containsExactlyInAnyOrder(".xlsx", ".csv");
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
  void aHeaderOnlySheetHasNoExtractableText() throws IOException {
    Path file = tempDir.resolve("nur-kopfzeile.xlsx");
    writeWorkbook(file, sheetHeaderOnly("Blatt1", List.of("Name", "Amt")));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "nur-kopfzeile.xlsx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
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
  void anEmptyCsvHasNoExtractableText() throws IOException {
    Path file = tempDir.resolve("leer.csv");
    Files.writeString(file, "", StandardCharsets.UTF_8);

    DocumentPipelineResult result = pipeline.run(DocumentPipelineSource.ofFile(file, "leer.csv"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
  }

  @Test
  void aHeaderOnlyCsvHasNoExtractableText() throws IOException {
    Path file = tempDir.resolve("nur-kopfzeile.csv");
    Files.writeString(file, "Name,Amt\n", StandardCharsets.UTF_8);

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "nur-kopfzeile.csv"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
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
  void extractedTextWithoutAFileIsParsedAsCsvToo() {
    DocumentPipelineResult result =
        pipeline.run(
            DocumentPipelineSource.ofExtractedText("Name,Amt\nMüller,Bauamt\n", "export.csv"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks().getFirst().getText()).contains("Müller | Bauamt");
  }
}
