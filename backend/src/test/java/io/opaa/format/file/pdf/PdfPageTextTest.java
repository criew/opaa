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
import org.apache.pdfbox.util.Matrix;
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

  @Test
  void aRaisedSmallerDigitIsSeparatedFromTheDigitOrLetterAfterIt() throws IOException {
    try (PDDocument doc = superscriptPage()) {
      String text = PdfPageText.extract(doc, 0);

      assertThat(text)
          .startsWith("1 10 Jahre nach Ablauf des Kalenderjahres.\n2 Mit Ausnahme der Akten.\n");
    }
  }

  @Test
  void textWithoutARaisedDigitBeforeADigitOrLetterIsUnchanged() throws IOException {
    try (PDDocument doc = superscriptPage()) {
      String text = PdfPageText.extract(doc, 0);

      assertThat(text)
          .endsWith(
              "Die Frist beträgt 10 Jahre und 2 Monate.\n"
                  + "Fläche 25 m² zu 3 €.\n"
                  + "Gemäß Satzung3 gilt dies.\n"
                  + "Fließtext ohne Hochstellung.\n");
    }
  }

  /**
   * Regression guard: a font size below 1 pt scaled up by the {@code cm} matrix must be judged by
   * its displayed size, so plain digits of equal size and baseline are never split.
   */
  @Test
  void digitsScaledUpFromASubPointFontStayTogether() throws IOException {
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
        stream.transform(Matrix.getScaleInstance(20, 20));
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 0.5f);
        stream.newLineAtOffset(2.5f, 38.5f);
        stream.showText("Frist 2024 und 10 Jahre");
        stream.endText();
      }

      assertThat(PdfPageText.extract(doc, 0)).isEqualTo("Frist 2024 und 10 Jahre\n");
    }
  }

  @Test
  void aRaisedDigitInATableCellIsSeparatedToo() throws IOException {
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
        text(stream, 54, 726, "Frist");
        superscriptFirst(stream, 204, 726, "1", "10 Jahre");
        text(stream, 54, 706, "Ausnahme");
        superscriptFirst(stream, 204, 706, "2", "Mit Akten");
        for (float y : new float[] {740, 720, 700}) {
          stream.moveTo(50, y);
          stream.lineTo(350, y);
        }
        for (float x : new float[] {50, 200, 350}) {
          stream.moveTo(x, 700);
          stream.lineTo(x, 740);
        }
        stream.stroke();
      }

      assertThat(PdfPageText.extract(doc, 0))
          .isEqualTo("Frist | 1 10 Jahre\nAusnahme | 2 Mit Akten\n");
    }
  }

  /**
   * Two lines opening with a sentence number set raised in a smaller size, then plain digits, the
   * Unicode superscript in "m²", a raised footnote mark before a space and plain flow text.
   */
  private static PDDocument superscriptPage() throws IOException {
    PDDocument doc = new PDDocument();
    PDPage page = new PDPage(PDRectangle.A4);
    doc.addPage(page);
    try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
      superscriptFirst(stream, 50, 770, "1", "10 Jahre nach Ablauf des Kalenderjahres.");
      superscriptFirst(stream, 50, 750, "2", "Mit Ausnahme der Akten.");
      text(stream, 50, 730, "Die Frist beträgt 10 Jahre und 2 Monate.");
      text(stream, 50, 710, "Fläche 25 m² zu 3 €.");
      stream.beginText();
      stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
      stream.newLineAtOffset(50, 690);
      stream.showText("Gemäß Satzung");
      stream.setTextRise(4);
      stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 6);
      stream.showText("3");
      stream.setTextRise(0);
      stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
      stream.showText(" gilt dies.");
      stream.endText();
      text(stream, 50, 670, "Fließtext ohne Hochstellung.");
    }
    return doc;
  }

  /** {@code mark} at 6 pt, raised by 4 pt, directly followed by {@code rest} at 10 pt. */
  private static void superscriptFirst(
      PDPageContentStream stream, float x, float y, String mark, String rest) throws IOException {
    stream.beginText();
    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 6);
    stream.setTextRise(4);
    stream.newLineAtOffset(x, y);
    stream.showText(mark);
    stream.setTextRise(0);
    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
    stream.showText(rest);
    stream.endText();
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
