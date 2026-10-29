package io.opaa.format.file.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.junit.jupiter.api.Test;

/** The counts {@link PdfLoading} takes before PDFBox registers objects, and where they stop. */
class PdfLoadingScanTest {

  @Test
  void classicTableLinesAreCountedUpToTheTrailerAndThePositionIsKept() throws IOException {
    String table =
        "xref\n0 3\r\n0000000000 65535 f \n9 0 n\n 1 0 n\ntrailer\n<< /Size 3 >>\n7 0 n\n";
    try (RandomAccessReadBuffer source = buffer("abc" + table)) {
      source.seek(3);

      assertThat(PdfLoading.classicTableLines(source)).isEqualTo(4);
      assertThat(source.getPosition()).isEqualTo(3);
    }
  }

  /**
   * Regression guard: PDFBox skips all six whitespace bytes and {@code %} comments before an entry,
   * so a line opening with a form feed or a NUL, or one after a comment line, is still an entry.
   */
  @Test
  void linesPdfBoxReadsAfterSkippedWhitespaceAndCommentsAreCounted() throws IOException {
    String table = "xref\n0 4\n\f9 0 n\n\u00009 0 n\n% Kommentar\n9 0 n\n\r\n 9 0 n\ntrailer\n";
    try (RandomAccessReadBuffer source = buffer(table)) {
      assertThat(PdfLoading.classicTableLines(source)).isEqualTo(5);
    }
  }

  /**
   * Regression guard: PDFBox reads a line as an entry unless it opens with {@code t} or a
   * delimiter, and {@code Long.parseLong} takes a leading {@code +}.
   */
  @Test
  void linesOpeningWithAnyOtherCharacterAreCountedUntilTrailerOrADelimiter() throws IOException {
    try (RandomAccessReadBuffer source = buffer("xref\n0 3\n+9 0 n\n-9 0 n\nx\ntrailer\n")) {
      assertThat(PdfLoading.classicTableLines(source)).isEqualTo(4);
    }
    try (RandomAccessReadBuffer source = buffer("xref\n0 2\n9 0 n\n<< /Size 2 >>\n9 0 n\n")) {
      assertThat(PdfLoading.classicTableLines(source)).isEqualTo(2);
    }
  }

  @Test
  void objectMarkersFollowingWhitespaceAreCountedAndThePositionIsKept() throws IOException {
    try (RandomAccessReadBuffer source =
        buffer("1 0 obj\nnull\nendobj\n2 0\tobj x obj xobj objx")) {
      source.seek(5);

      assertThat(PdfLoading.objectMarkers(source)).isEqualTo(4);
      assertThat(source.getPosition()).isEqualTo(5);
    }
  }

  private static RandomAccessReadBuffer buffer(String text) {
    return new RandomAccessReadBuffer(text.getBytes(StandardCharsets.US_ASCII));
  }
}
