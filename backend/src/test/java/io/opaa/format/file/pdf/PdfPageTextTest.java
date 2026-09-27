package io.opaa.format.file.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/**
 * The page-level contract of {@link PdfPageText}: a failure or an ambiguity on the table path costs
 * the page its table form, never its text, and every line - the last one included - ends in {@code
 * \n}.
 */
class PdfPageTextTest {

  @Test
  void aFailingGridScanFallsBackToFlowText() throws IOException {
    try (PDDocument doc = tablePage()) {
      String expected = PdfPageText.extract(doc, 0, page -> List.of());

      String unchecked =
          PdfPageText.extract(
              doc,
              0,
              page -> {
                throw new IllegalStateException("broken operator");
              });
      String checked =
          PdfPageText.extract(
              doc,
              0,
              page -> {
                throw new IOException("broken stream");
              });

      assertThat(expected).contains("Datum Standort");
      assertThat(unchecked).isEqualTo(expected);
      assertThat(checked).isEqualTo(expected);
    }
  }

  @Test
  void everyLineEndsInANewlineOnEveryPlatform() throws IOException {
    try (PDDocument doc = tablePage()) {
      String text = PdfPageText.extract(doc, 0);

      assertThat(text)
          .doesNotContain("\r")
          .isEqualTo("Vorbemerkung.\nDatum | Standort\n4. August 2026 | Rheinau\nSchluss.\n");
    }
  }

  @Test
  void eachTableReplacesItsMarkerOnLinesOfItsOwn() {
    String text =
        "Vorher " + PdfPageText.marker(0) + " \nMitte\n" + PdfPageText.marker(1) + "\nEnde\n";

    assertThat(PdfPageText.placeTables(text, List.of("a | b", "c | d")))
        .isEqualTo("Vorher\na | b\nMitte\nc | d\nEnde\n");
  }

  @Test
  void aMissingOrRepeatedMarkerYieldsNoPlacement() {
    String marker = PdfPageText.marker(0);

    assertThat(PdfPageText.placeTables("kein Anker\n", List.of("a | b"))).isNull();
    assertThat(PdfPageText.placeTables(marker + "\n" + marker + "\n", List.of("a | b"))).isNull();
  }

  /** "Vorbemerkung.", a ruled 2x2 table, "Schluss." - top to bottom. */
  private static PDDocument tablePage() throws IOException {
    PDDocument doc = new PDDocument();
    PDPage page = new PDPage(PDRectangle.A4);
    doc.addPage(page);
    try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
      text(stream, 50, 770, "Vorbemerkung.");
      text(stream, 54, 726, "Datum");
      text(stream, 204, 726, "Standort");
      text(stream, 54, 706, "4. August 2026");
      text(stream, 204, 706, "Rheinau");
      for (float y : new float[] {740, 720, 700}) {
        stream.moveTo(50, y);
        stream.lineTo(350, y);
      }
      for (float x : new float[] {50, 200, 350}) {
        stream.moveTo(x, 700);
        stream.lineTo(x, 740);
      }
      stream.stroke();
      text(stream, 50, 650, "Schluss.");
    }
    return doc;
  }

  private static void text(PDPageContentStream stream, float x, float y, String text)
      throws IOException {
    stream.beginText();
    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
    stream.newLineAtOffset(x, y);
    stream.showText(text);
    stream.endText();
  }
}
