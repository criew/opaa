package io.opaa.format.file.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatSource;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A table whose cells are enclosed by a complete ruled grid is emitted in the shared table form
 * (cells joined by {@code " | "}, one line per row) at its place in the page text; every other page
 * content - and every ruling that does not form such a grid - stays flow text as before.
 */
class PdfDocumentFormatTableTest {

  private static final float ROW_HEIGHT = 20;

  @TempDir Path tempDir;

  private final PdfDocumentFormat pipeline = new PdfDocumentFormat();

  // regression guard for #2033: the demo's Einsatzplan, where a model read the space-separated
  // rows with cells shifted into the neighbouring row
  @Test
  void theEinsatzplanOfTheDemoCorpusKeepsEveryCellInItsRow() throws Exception {
    Path file = resource("/test-documents/pdf/einsatzplan-mobiles-buergerbuero-2026-q3.pdf");

    String text = onlyChunkText(file);

    assertThat(text)
        .contains(
            String.join(
                "\n",
                "Datum | Wochentag | Standort | Besetzung",
                "7. Juli 2026 | Dienstag | Stadtteilzentrum Rheinau | Selin Kaya",
                "8. Juli 2026 | Mittwoch | Gemeindezentrum Nordfeld | Maria Weber, Andrea Vogt",
                "16. Juli 2026 | Donnerstag | Bürgertreff Weststadt | Selin Kaya",
                "4. August 2026 | Dienstag | Stadtteilzentrum Rheinau | Maria Weber",
                "12. August 2026 | Mittwoch | Gemeindezentrum Nordfeld | Selin Kaya",
                "20. August 2026 | Donnerstag | Bürgertreff Weststadt | Maria Weber",
                "1. September 2026 | Dienstag | Stadtteilzentrum Rheinau | Selin Kaya",
                "9. September 2026 | Mittwoch | Gemeindezentrum Nordfeld | Maria Weber",
                "17. September 2026 | Donnerstag | Bürgertreff Weststadt | Selin Kaya"));
    // The flow text around the table keeps its place and appears exactly once.
    assertThat(text.indexOf("die Leitung des Bürgerbüros.")).isLessThan(text.indexOf("Datum |"));
    assertThat(text.indexOf("Mitzunehmen")).isGreaterThan(text.indexOf("17. September 2026 |"));
    assertThat(text).containsOnlyOnce("Mitzunehmen").containsOnlyOnce("Wochentag");
  }

  @Test
  void aGridOfStrokedLinesBecomesOneLinePerRowBetweenItsSurroundingText() throws IOException {
    Path file = tempDir.resolve("gitter.pdf");
    Grid grid =
        new Grid(
            List.of(50f, 180f, 360f, 480f),
            740,
            List.of(
                List.of("Datum", "Standort", "Besetzung"),
                List.of("4. August 2026", "Stadtteilzentrum Rheinau", "Maria Weber"),
                List.of("12. August 2026", "Gemeindezentrum Nordfeld", "Selin Kaya")));
    writePage(
        file,
        stream -> {
          text(stream, 50, 770, "Vorbemerkung zum Einsatzplan.");
          grid.drawCellTexts(stream);
          grid.strokeAllLines(stream);
          text(stream, 50, 650, "Tauschwuensche bitte bis Ende Juni.");
        });

    String text = onlyChunkText(file);

    assertThat(text)
        .isEqualTo(
            String.join(
                "\n",
                "Vorbemerkung zum Einsatzplan.",
                "Datum | Standort | Besetzung",
                "4. August 2026 | Stadtteilzentrum Rheinau | Maria Weber",
                "12. August 2026 | Gemeindezentrum Nordfeld | Selin Kaya",
                "Tauschwuensche bitte bis Ende Juni."));
  }

  @Test
  void aGridDrawnAsThinFilledRectanglesBeforeItsTextIsRecognisedToo() throws IOException {
    Path file = tempDir.resolve("gitter-flaechen.pdf");
    Grid grid =
        new Grid(
            List.of(60f, 200f, 330f),
            700,
            List.of(
                List.of("Leistung", "Gebuehr"),
                List.of("Personalausweis", "37,00 EUR"),
                List.of("Reisepass", "70,00 EUR")));
    writePage(
        file,
        stream -> {
          grid.fillAllLinesAsThinRectangles(stream);
          grid.drawCellTexts(stream);
        });

    assertThat(onlyChunkText(file))
        .isEqualTo(
            String.join(
                "\n",
                "Leistung | Gebuehr",
                "Personalausweis | 37,00 EUR",
                "Reisepass | 70,00 EUR"));
  }

  @Test
  void aCellWhoseTextWrapsOverTwoLinesStaysOneCell() throws IOException {
    Path file = tempDir.resolve("umbruch.pdf");
    writePage(
        file,
        stream -> {
          // two columns, header row of 20 pt and one data row of 34 pt with a wrapped cell
          text(stream, 54, 726, "Standort");
          text(stream, 204, 726, "Besetzung");
          text(stream, 54, 706, "Gemeindezentrum");
          text(stream, 54, 692, "Nordfeld");
          text(stream, 204, 706, "Selin Kaya");
          hLine(stream, 740, 50, 350);
          hLine(stream, 720, 50, 350);
          hLine(stream, 686, 50, 350);
          vLine(stream, 50, 686, 740);
          vLine(stream, 200, 686, 740);
          vLine(stream, 350, 686, 740);
          stream.stroke();
        });

    assertThat(onlyChunkText(file))
        .isEqualTo(
            String.join("\n", "Standort | Besetzung", "Gemeindezentrum Nordfeld | Selin Kaya"));
  }

  @Test
  void aTableEndingAPageDoesNotRunIntoTheNextPagesText() throws IOException {
    // With an outline, the text of consecutive pages is concatenated into one section.
    Path file = tempDir.resolve("seitenwechsel.pdf");
    Grid grid =
        new Grid(
            List.of(50f, 250f, 400f),
            740,
            List.of(
                List.of("Gebuehrentatbestand", "Betrag"), List.of("Fuehrungszeugnis", "15 Euro")));
    try (PDDocument doc = new PDDocument()) {
      PDPage first = new PDPage(PDRectangle.A4);
      doc.addPage(first);
      try (PDPageContentStream stream = new PDPageContentStream(doc, first)) {
        text(stream, 50, 770, "Anlage: Gebuehrenverzeichnis");
        grid.drawCellTexts(stream);
        grid.strokeAllLines(stream);
      }
      PDPage second = new PDPage(PDRectangle.A4);
      doc.addPage(second);
      try (PDPageContentStream stream = new PDPageContentStream(doc, second)) {
        text(stream, 50, 770, "Schlussbestimmung");
      }
      PDDocumentOutline outline = new PDDocumentOutline();
      doc.getDocumentCatalog().setDocumentOutline(outline);
      PDOutlineItem item = new PDOutlineItem();
      item.setTitle("Anlage: Gebuehrenverzeichnis");
      PDPageXYZDestination destination = new PDPageXYZDestination();
      destination.setPage(first);
      item.setDestination(destination);
      outline.addLast(item);
      doc.save(file.toFile());
    }

    assertThat(onlyChunkText(file))
        .contains(
            String.join(
                "\n",
                "Gebuehrentatbestand | Betrag",
                "Fuehrungszeugnis | 15 Euro",
                "Schlussbestimmung"));
  }

  @Test
  void twoTablesOnOnePageAreEachWrittenAtTheirOwnPlace() throws IOException {
    Path file = tempDir.resolve("zwei-tabellen.pdf");
    Grid first =
        new Grid(
            List.of(50f, 200f, 350f),
            780,
            List.of(List.of("Datum", "Standort"), List.of("4. August 2026", "Rheinau")));
    Grid second =
        new Grid(
            List.of(50f, 200f, 350f),
            660,
            List.of(List.of("Leistung", "Gebuehr"), List.of("Reisepass", "70,00 EUR")));
    writePage(
        file,
        stream -> {
          first.drawCellTexts(stream);
          first.strokeAllLines(stream);
          text(stream, 50, 700, "Zwischen den Tabellen.");
          second.drawCellTexts(stream);
          second.strokeAllLines(stream);
        });

    assertThat(onlyChunkText(file))
        .isEqualTo(
            String.join(
                "\n",
                "Datum | Standort",
                "4. August 2026 | Rheinau",
                "Zwischen den Tabellen.",
                "Leistung | Gebuehr",
                "Reisepass | 70,00 EUR"));
  }

  @Test
  void aTableOnAPageWithAnOffsetCropBoxIsRecognised() throws IOException {
    Path file = tempDir.resolve("cropbox.pdf");
    Grid grid =
        new Grid(
            List.of(150f, 300f, 450f),
            600,
            List.of(List.of("Datum", "Standort"), List.of("4. August 2026", "Rheinau")));
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      page.setCropBox(new PDRectangle(100, 450, 400, 250));
      doc.addPage(page);
      try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
        grid.drawCellTexts(stream);
        grid.strokeAllLines(stream);
      }
      doc.save(file.toFile());
    }

    assertThat(onlyChunkText(file))
        .isEqualTo(String.join("\n", "Datum | Standort", "4. August 2026 | Rheinau"));
  }

  @Test
  void aTableOnARotatedPageStaysFlowText() throws IOException {
    Path file = tempDir.resolve("gedreht.pdf");
    Grid grid =
        new Grid(
            List.of(50f, 200f, 350f),
            740,
            List.of(List.of("Datum", "Standort"), List.of("4. August 2026", "Rheinau")));
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      page.setRotation(90);
      doc.addPage(page);
      try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
        grid.drawCellTexts(stream);
        grid.strokeAllLines(stream);
      }
      doc.save(file.toFile());
    }

    String flowText;
    try (PDDocument doc = Loader.loadPDF(file.toFile())) {
      flowText = PdfPageText.extract(doc, 0, page -> List.of()).strip();
    }
    assertThat(onlyChunkText(file)).doesNotContain(" | ").isEqualTo(flowText);
  }

  @Test
  void aCellTextStartingOnItsColumnLineStaysInItsCell() throws IOException {
    // Zero cell padding: the first glyph's origin lies a fraction left of the column line.
    Path file = tempDir.resolve("ohne-innenabstand.pdf");
    Grid grid = new Grid(List.of(50f, 200f, 380f), 740, List.of(List.of(), List.of()));
    writePage(
        file,
        stream -> {
          text(stream, 54, 726, "Datum");
          text(stream, 199.5f, 726, "Standort");
          text(stream, 54, 706, "4. August 2026");
          text(stream, 199.5f, 706, "Gemeindezentrum Nordfeld");
          grid.strokeAllLines(stream);
        });

    assertThat(onlyChunkText(file))
        .isEqualTo(
            String.join("\n", "Datum | Standort", "4. August 2026 | Gemeindezentrum Nordfeld"));
  }

  @Test
  void aPageWithMoreRulingsThanTheCapStaysFlowText() throws IOException {
    Path file = tempDir.resolve("viele-linien.pdf");
    Grid grid =
        new Grid(
            List.of(50f, 200f, 350f),
            740,
            List.of(List.of("Datum", "Standort"), List.of("4. August 2026", "Rheinau")));
    writePage(
        file,
        stream -> {
          grid.drawCellTexts(stream);
          grid.strokeAllLines(stream);
          for (int i = 0; i <= PdfRulingCollector.MAX_RULINGS; i++) {
            hLine(stream, 100 + (i % 200), 400 + (i / 200) * 10, 405 + (i / 200) * 10);
          }
          stream.stroke();
        });

    assertThat(onlyChunkText(file)).doesNotContain(" | ").contains("4. August 2026", "Rheinau");
  }

  @Test
  void aPathWithMorePointsThanTheBudgetStaysFlowText() throws IOException {
    // Path construction without a painting operator: every point is held until the path ends.
    // 50,000 points lie well above PdfRulingCollector's path-point budget.
    Path file = tempDir.resolve("viele-punkte.pdf");
    Grid grid =
        new Grid(
            List.of(50f, 200f, 350f),
            740,
            List.of(List.of("Datum", "Standort"), List.of("4. August 2026", "Rheinau")));
    writePage(
        file,
        stream -> {
          for (int i = 0; i < 50_000; i++) {
            stream.moveTo(10, 10);
          }
          stream.fill();
          grid.drawCellTexts(stream);
          grid.strokeAllLines(stream);
        });

    assertThat(onlyChunkText(file)).doesNotContain(" | ").contains("4. August 2026", "Rheinau");
  }

  // regression guard for #2033: 20 million path points without a painting operator fit into a
  // small Flate stream; the ruling scan must not hold them, or the heap runs out
  @Test
  void aCompressedStreamOfUnpaintedPathPointsDoesNotExhaustTheHeap() throws IOException {
    Path file = tempDir.resolve("pfadbombe.pdf");
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
        text(stream, 50, 770, "Hinweis vor der Grafik.");
      }
      PDStream bomb = new PDStream(doc);
      try (OutputStream out = bomb.createOutputStream(COSName.FLATE_DECODE)) {
        byte[] moveTo = "0 0 m\n".getBytes(StandardCharsets.US_ASCII);
        byte[] block = new byte[moveTo.length * 10_000];
        for (int i = 0; i < 10_000; i++) {
          System.arraycopy(moveTo, 0, block, i * moveTo.length, moveTo.length);
        }
        for (int i = 0; i < 2_000; i++) {
          out.write(block);
        }
      }
      List<PDStream> contents = new ArrayList<>();
      page.getContentStreams().forEachRemaining(contents::add);
      contents.add(bomb);
      page.setContents(contents);
      doc.save(file.toFile());
    }

    assertThat(onlyChunkText(file)).isEqualTo("Hinweis vor der Grafik.");
  }

  @Test
  void aGridWithMoreCellsThanTheCapStaysFlowText() throws IOException {
    Path file = tempDir.resolve("riesengitter.pdf");
    int lines = 72;
    assertThat((lines - 1) * (lines - 1)).isGreaterThan(PdfTableGrids.MAX_CELLS);
    writePage(
        file,
        stream -> {
          text(stream, 52, 792, "a1");
          text(stream, 52, 782, "a2");
          text(stream, 80, 792, "b1");
          text(stream, 80, 782, "b2");
          for (int i = 0; i < lines; i++) {
            hLine(stream, 800 - i * 10, 50, 50 + (lines - 1) * 7);
            vLine(stream, 50 + i * 7, 800 - (lines - 1) * 10, 800);
          }
          stream.stroke();
        });

    assertThat(onlyChunkText(file)).doesNotContain(" | ").contains("a1", "b2");
  }

  @Test
  void columnAlignedTextWithoutRulingsStaysFlowText() throws IOException {
    Path file = tempDir.resolve("ohne-linien.pdf");
    Grid grid =
        new Grid(
            List.of(50f, 180f, 360f),
            740,
            List.of(List.of("Datum", "Standort"), List.of("4. August 2026", "Rheinau")));
    writePage(file, grid::drawCellTexts);

    assertThat(onlyChunkText(file))
        .doesNotContain(" | ")
        .isEqualTo(String.join("\n", "Datum Standort", "4. August 2026 Rheinau"));
  }

  @Test
  void horizontalRulesAloneDoNotMakeATable() throws IOException {
    // A letterhead or a booktabs-style table: rules above and below, no column lines.
    Path file = tempDir.resolve("nur-querlinien.pdf");
    Grid grid =
        new Grid(
            List.of(50f, 180f, 360f),
            740,
            List.of(List.of("Datum", "Standort"), List.of("4. August 2026", "Rheinau")));
    writePage(
        file,
        stream -> {
          grid.drawCellTexts(stream);
          hLine(stream, 740, 50, 360);
          hLine(stream, 720, 50, 360);
          hLine(stream, 700, 50, 360);
          stream.stroke();
        });

    assertThat(onlyChunkText(file))
        .doesNotContain(" | ")
        .isEqualTo(String.join("\n", "Datum Standort", "4. August 2026 Rheinau"));
  }

  @Test
  void aGridWithAMergedCellStaysFlowText() throws IOException {
    // The column line between the two cells stops below the header row, which spans both columns.
    Path file = tempDir.resolve("verbunden.pdf");
    writePage(
        file,
        stream -> {
          text(stream, 54, 726, "Einsaetze im August");
          text(stream, 54, 706, "4. August 2026");
          text(stream, 184, 706, "Rheinau");
          text(stream, 54, 686, "12. August 2026");
          text(stream, 184, 686, "Nordfeld");
          hLine(stream, 740, 50, 360);
          hLine(stream, 720, 50, 360);
          hLine(stream, 700, 50, 360);
          hLine(stream, 680, 50, 360);
          vLine(stream, 50, 680, 740);
          vLine(stream, 180, 680, 720);
          vLine(stream, 360, 680, 740);
          stream.stroke();
        });

    String text = onlyChunkText(file);

    assertThat(text)
        .doesNotContain(" | ")
        .contains("Einsaetze im August", "4. August 2026 Rheinau", "12. August 2026 Nordfeld");
  }

  @Test
  void aBoxAroundAParagraphIsNotATable() throws IOException {
    Path file = tempDir.resolve("kasten.pdf");
    writePage(
        file,
        stream -> {
          text(stream, 60, 720, "Hinweis: Bitte den Dienstausweis mitnehmen.");
          stream.addRect(50, 700, 400, 40);
          stream.stroke();
        });

    assertThat(onlyChunkText(file)).isEqualTo("Hinweis: Bitte den Dienstausweis mitnehmen.");
  }

  @Test
  void aRuledGridWithTextInOnlyOneColumnStaysFlowText() throws IOException {
    // A form with empty answer boxes: the grid is complete, but nothing in it is tabular data.
    Path file = tempDir.resolve("formular.pdf");
    Grid grid =
        new Grid(
            List.of(50f, 180f, 360f),
            740,
            List.of(List.of("Name", ""), List.of("Datum", ""), List.of("Unterschrift", "")));
    writePage(
        file,
        stream -> {
          grid.drawCellTexts(stream);
          grid.strokeAllLines(stream);
        });

    assertThat(onlyChunkText(file))
        .doesNotContain(" | ")
        .isEqualTo(String.join("\n", "Name", "Datum", "Unterschrift"));
  }

  private String onlyChunkText(Path file) {
    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofFile(file, file.getFileName().toString(), ".pdf"));
    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    return result.chunks().getFirst().getText();
  }

  @FunctionalInterface
  private interface PageContent {
    void draw(PDPageContentStream stream) throws IOException;
  }

  private static void writePage(Path file, PageContent content) throws IOException {
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
        content.draw(stream);
      }
      doc.save(file.toFile());
    }
  }

  private static void text(PDPageContentStream stream, float x, float y, String text)
      throws IOException {
    stream.beginText();
    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
    stream.newLineAtOffset(x, y);
    stream.showText(text);
    stream.endText();
  }

  private static void hLine(PDPageContentStream stream, float y, float x1, float x2)
      throws IOException {
    stream.moveTo(x1, y);
    stream.lineTo(x2, y);
  }

  private static void vLine(PDPageContentStream stream, float x, float y1, float y2)
      throws IOException {
    stream.moveTo(x, y1);
    stream.lineTo(x, y2);
  }

  /**
   * A table of equal-height rows: {@code columnXs} are the column lines left to right, {@code top}
   * the upper edge in PDF coordinates (y up), each cell's text is set 4 pt right of its column line
   * and 14 pt below its row's upper edge.
   */
  private record Grid(List<Float> columnXs, float top, List<List<String>> rows) {

    float bottom() {
      return top - ROW_HEIGHT * rows.size();
    }

    List<Float> rowYs() {
      List<Float> ys = new ArrayList<>();
      for (int i = 0; i <= rows.size(); i++) {
        ys.add(top - ROW_HEIGHT * i);
      }
      return ys;
    }

    void drawCellTexts(PDPageContentStream stream) throws IOException {
      for (int r = 0; r < rows.size(); r++) {
        List<String> cells = rows.get(r);
        for (int c = 0; c < cells.size(); c++) {
          if (!cells.get(c).isEmpty()) {
            text(stream, columnXs.get(c) + 4, top - ROW_HEIGHT * r - 14, cells.get(c));
          }
        }
      }
    }

    void strokeAllLines(PDPageContentStream stream) throws IOException {
      float left = columnXs.getFirst();
      float right = columnXs.getLast();
      for (float y : rowYs()) {
        hLine(stream, y, left, right);
      }
      for (float x : columnXs) {
        vLine(stream, x, bottom(), top);
      }
      stream.stroke();
    }

    void fillAllLinesAsThinRectangles(PDPageContentStream stream) throws IOException {
      float left = columnXs.getFirst();
      float right = columnXs.getLast();
      for (float y : rowYs()) {
        stream.addRect(left, y - 0.25f, right - left, 0.5f);
      }
      for (float x : columnXs) {
        stream.addRect(x - 0.25f, bottom(), 0.5f, top - bottom());
      }
      stream.fill();
    }
  }

  private static Path resource(String name) throws URISyntaxException {
    return Path.of(
        Objects.requireNonNull(PdfDocumentFormatTableTest.class.getResource(name)).toURI());
  }
}
