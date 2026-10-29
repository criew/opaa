package io.opaa.format.file.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.shared.HeadingSectionSplitterFixture;
import io.opaa.format.shared.ParserLimitExceededException;
import io.opaa.format.shared.ParserLimitProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A few hundred bytes of PDF must not cost memory or time out of proportion: a page tree is read by
 * walking it, never by trusting {@code /Count}; every page is extracted without walking the whole
 * tree again; and the cross-reference and object streams PDFBox decodes while it loads a file are
 * measured against {@link ParserLimitProperties#maxPdfStreamBytes()} before it decodes them into
 * memory, also on the brute-force path for a broken cross-reference.
 */
class PdfAmplificationTest {

  /** What the bombs below inflate to: harmless for the heap, four times the stream limit. */
  private static final int BOMB_BYTES = 16 * 1024 * 1024;

  private static final ParserLimitProperties FOUR_MIB_STREAMS =
      new ParserLimitProperties(0, 0, 0, 0, 0, 4L * 1024 * 1024, 0, 0);

  @TempDir Path tempDir;

  private final PdfDocumentFormat pipeline =
      new PdfDocumentFormat(HeadingSectionSplitterFixture.DEFAULT);

  private final PdfDocumentFormat limited =
      new PdfDocumentFormat(HeadingSectionSplitterFixture.DEFAULT, FOUR_MIB_STREAMS);

  /**
   * Regression guard: a page tree of one page claiming two billion pages in {@code /Count} must not
   * size anything after that number.
   */
  @Test
  void aPageCountOfTwoBillionIsNotTrusted() throws IOException {
    Path file = tempDir.resolve("count.pdf");
    RawPdf pdf = new RawPdf();
    pdf.object(1, "<< /Type /Catalog /Pages 2 0 R >>");
    pdf.object(2, "<< /Type /Pages /Kids [3 0 R] /Count 2000000000 >>");
    pdf.object(3, page(2, 4));
    pdf.stream(4, "", textContent("Eine Seite Text."));
    Files.write(file, pdf.withXrefTable(1));

    DocumentFormatResult result = pipeline.run(DocumentFormatSource.ofFile(file, "count.pdf"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(result.chunks())
        .singleElement()
        .extracting(c -> c.getText())
        .asString()
        .contains("Eine Seite Text.");
  }

  /**
   * Regression guard: one page listed thousands of times in {@code /Kids} is that many pages, each
   * extracted without walking the whole tree again - the walk per page made this quadratic.
   */
  @Test
  void aPageTreeRepeatingOnePageIsReadInLinearTime() throws IOException {
    int repeats = 10_000;
    Path file = tempDir.resolve("kids.pdf");
    RawPdf pdf = new RawPdf();
    pdf.object(1, "<< /Type /Catalog /Pages 2 0 R >>");
    pdf.object(
        2, "<< /Type /Pages /Kids [" + "3 0 R ".repeat(repeats) + "] /Count " + repeats + " >>");
    pdf.object(3, page(2, 4));
    pdf.stream(4, "", textContent("x"));
    Files.write(file, pdf.withXrefTable(1));

    DocumentFormatResult result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(8),
            () -> pipeline.run(DocumentFormatSource.ofFile(file, "kids.pdf")));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
  }

  /**
   * Regression guard: the catalog sits in an object stream that inflates to four times the stream
   * limit; PDFBox would decode it into memory as a whole while loading the file.
   */
  @Test
  void anObjectStreamPastTheStreamLimitIsRefusedBeforeLoading() throws IOException {
    Path file = tempDir.resolve("objstm.pdf");
    RawPdf pdf = new RawPdf();
    String header = "1 0 ";
    String catalog = "<< /Type /Catalog /Pages 2 0 R >>";
    pdf.stream(
        5,
        "/Type /ObjStm /N 1 /First " + header.length() + " /Filter /FlateDecode",
        deflated(ascii(header + catalog), BOMB_BYTES));
    pdf.object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
    pdf.object(3, page(2, 4));
    pdf.stream(4, "", textContent("Text."));
    Files.write(file, pdf.withXrefStream(6, 1, 5, -1));

    assertThatThrownBy(() -> limited.run(DocumentFormatSource.ofFile(file, "objstm.pdf")))
        .isInstanceOf(ParserLimitExceededException.class);
  }

  /** Regression guard: the cross-reference stream itself inflates past the stream limit. */
  @Test
  void aCrossReferenceStreamPastTheStreamLimitIsRefusedBeforeLoading() throws IOException {
    Path file = tempDir.resolve("xref.pdf");
    RawPdf pdf = new RawPdf();
    pdf.object(1, "<< /Type /Catalog /Pages 2 0 R >>");
    pdf.object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
    pdf.object(3, page(2, 4));
    pdf.stream(4, "", textContent("Text."));
    Files.write(file, pdf.withXrefStream(5, 1, -1, BOMB_BYTES));

    assertThatThrownBy(() -> limited.run(DocumentFormatSource.ofFile(file, "xref.pdf")))
        .isInstanceOf(ParserLimitExceededException.class);
  }

  /**
   * Regression guard: without a usable cross-reference PDFBox searches the file for object streams
   * and decodes every one it finds, through a parser of its own.
   */
  @Test
  void anObjectStreamFoundByTheBruteForceSearchIsMeasuredToo() throws IOException {
    Path file = tempDir.resolve("broken.pdf");
    RawPdf pdf = new RawPdf();
    pdf.object(1, "<< /Type /Catalog /Pages 2 0 R >>");
    pdf.object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
    pdf.object(3, page(2, 4));
    pdf.stream(4, "", textContent("Text."));
    String header = "6 0 ";
    pdf.stream(
        5,
        "/Type /ObjStm /N 1 /First " + header.length() + " /Filter /FlateDecode",
        deflated(ascii(header + "<< /Unused true >>"), BOMB_BYTES));
    Files.write(file, pdf.withBrokenXref(1));

    assertThatThrownBy(() -> limited.run(DocumentFormatSource.ofFile(file, "broken.pdf")))
        .isInstanceOf(ParserLimitExceededException.class);
  }

  /** A well-formed file with object and cross-reference streams below the limit still reads. */
  @Test
  void streamsWithinTheLimitStillLoad() throws IOException {
    Path file = tempDir.resolve("fine.pdf");
    RawPdf pdf = new RawPdf();
    String header = "1 0 ";
    pdf.stream(
        5,
        "/Type /ObjStm /N 1 /First " + header.length() + " /Filter /FlateDecode",
        deflated(ascii(header + "<< /Type /Catalog /Pages 2 0 R >>"), 1024));
    pdf.object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
    pdf.object(3, page(2, 4));
    pdf.stream(4, "", textContent("Text im Objektstrom-PDF."));
    Files.write(file, pdf.withXrefStream(6, 1, 5, 1024));

    DocumentFormatResult result = limited.run(DocumentFormatSource.ofFile(file, "fine.pdf"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(result.chunks().getFirst().getText()).contains("Text im Objektstrom-PDF.");
  }

  private static String page(int parent, int contents) {
    return "<< /Type /Page /Parent "
        + parent
        + " 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 << /Type /Font /Subtype"
        + " /Type1 /BaseFont /Helvetica >> >> >> /Contents "
        + contents
        + " 0 R >>";
  }

  private static byte[] textContent(String text) {
    return ascii("BT /F1 12 Tf 72 700 Td (" + text + ") Tj ET\n");
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  /** {@code head} followed by {@code padding} spaces, deflated. */
  private static byte[] deflated(byte[] head, int padding) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DeflaterOutputStream out =
        new DeflaterOutputStream(bytes, new Deflater(Deflater.BEST_COMPRESSION))) {
      out.write(head);
      byte[] spaces = new byte[64 * 1024];
      java.util.Arrays.fill(spaces, (byte) ' ');
      for (int left = padding; left > 0; left -= spaces.length) {
        out.write(spaces, 0, Math.min(left, spaces.length));
      }
    }
    return bytes.toByteArray();
  }

  /** A PDF written byte by byte, so its cross-reference can be shaped freely. */
  private static final class RawPdf {

    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private final List<long[]> offsets = new ArrayList<>();

    RawPdf() {
      write(ascii("%PDF-1.7\n%âãÏÓ\n"));
    }

    void object(int number, String dictionary) {
      offsets.add(new long[] {number, body.size()});
      write(ascii(number + " 0 obj\n" + dictionary + "\nendobj\n"));
    }

    void stream(int number, String entries, byte[] data) {
      offsets.add(new long[] {number, body.size()});
      write(ascii(number + " 0 obj\n<< " + entries + " /Length " + data.length + " >>\nstream\n"));
      write(data);
      write(ascii("\nendstream\nendobj\n"));
    }

    /** A classic cross-reference table over the objects written so far. */
    byte[] withXrefTable(int root) {
      int size = maxNumber() + 1;
      long start = body.size();
      StringBuilder table = new StringBuilder("xref\n0 " + size + "\n0000000000 65535 f \n");
      for (int n = 1; n < size; n++) {
        long offset = offsetOf(n);
        table.append(
            offset < 0 ? "0000000000 65535 f \n" : String.format("%010d 00000 n \n", offset));
      }
      table.append("trailer\n<< /Size ").append(size).append(" /Root ").append(root);
      table.append(" 0 R >>\nstartxref\n").append(start).append("\n%%EOF\n");
      write(ascii(table.toString()));
      return body.toByteArray();
    }

    /**
     * A cross-reference stream as object {@code number}; with {@code objectStream} at or above 0
     * the catalog {@code root} is entry 0 of that object stream. With {@code padding} at or above 0
     * the entries are followed by that many spaces and deflated.
     */
    byte[] withXrefStream(int number, int root, int objectStream, int padding) throws IOException {
      int size = Math.max(maxNumber(), number) + 1;
      long start = body.size();
      ByteArrayOutputStream entries = new ByteArrayOutputStream();
      for (int n = 0; n < size; n++) {
        if (n == root && objectStream >= 0) {
          entries.write(new byte[] {2, 0, 0, 0, (byte) objectStream, 0, 0});
        } else if (n == number) {
          entries.write(entry(1, start));
        } else {
          long offset = offsetOf(n);
          entries.write(offset < 0 ? new byte[7] : entry(1, offset));
        }
      }
      byte[] data = padding < 0 ? entries.toByteArray() : deflated(entries.toByteArray(), padding);
      String filter = padding < 0 ? "" : "/Filter /FlateDecode";
      write(
          ascii(
              number
                  + " 0 obj\n<< /Type /XRef /Size "
                  + size
                  + " /W [1 4 2] /Root "
                  + root
                  + " 0 R "
                  + filter
                  + " /Length "
                  + data.length
                  + " >>\nstream\n"));
      write(data);
      write(ascii("\nendstream\nendobj\nstartxref\n" + start + "\n%%EOF\n"));
      return body.toByteArray();
    }

    /** A trailer naming the catalog, behind a {@code startxref} that points nowhere. */
    byte[] withBrokenXref(int root) {
      write(
          ascii(
              "trailer\n<< /Size "
                  + (maxNumber() + 1)
                  + " /Root "
                  + root
                  + " 0 R >>\nstartxref\n999999999\n%%EOF\n"));
      return body.toByteArray();
    }

    private static byte[] entry(int type, long offset) {
      return new byte[] {
        (byte) type,
        (byte) (offset >> 24),
        (byte) (offset >> 16),
        (byte) (offset >> 8),
        (byte) offset,
        0,
        0
      };
    }

    private int maxNumber() {
      return (int) offsets.stream().mapToLong(o -> o[0]).max().orElse(0);
    }

    private long offsetOf(int number) {
      return offsets.stream()
          .filter(o -> o[0] == number)
          .mapToLong(o -> o[1])
          .findFirst()
          .orElse(-1);
    }

    private void write(byte[] bytes) {
      body.write(bytes, 0, bytes.length);
    }
  }
}
