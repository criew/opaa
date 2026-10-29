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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
      new ParserLimitProperties(0, 0, 0, 0, 0, 4L * 1024 * 1024, 0, 0, 0);

  private static final ParserLimitProperties TEN_THOUSAND_OBJECTS =
      new ParserLimitProperties(0, 0, 0, 0, 0, 0, 0, 0, 10_000);

  @TempDir Path tempDir;

  private final PdfDocumentFormat pipeline =
      new PdfDocumentFormat(HeadingSectionSplitterFixture.DEFAULT);

  private final PdfDocumentFormat limited =
      new PdfDocumentFormat(HeadingSectionSplitterFixture.DEFAULT, FOUR_MIB_STREAMS);

  private final PdfDocumentFormat fewObjects =
      new PdfDocumentFormat(HeadingSectionSplitterFixture.DEFAULT, TEN_THOUSAND_OBJECTS);

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

  /**
   * Regression guard: an older cross-reference stream along {@code /Prev} with entries of no width
   * lets PDFBox read entries without consuming a byte, as many as {@code /Index} claims.
   */
  @Test
  void aCrossReferenceStreamWithEntriesOfNoWidthIsRefused() throws IOException {
    Path file = tempDir.resolve("w000.pdf");
    RawPdf pdf = onePage();
    Files.write(
        file, pdf.withPreviousXrefStream("/W [0 0 0] /Index [100 3000000]", new byte[] {'x'}, 1));

    assertThatThrownBy(() -> pipeline.run(DocumentFormatSource.ofFile(file, "w000.pdf")))
        .isInstanceOf(ParserLimitExceededException.class)
        .hasMessageContaining("no width");
  }

  /**
   * Regression guard: an older cross-reference stream along {@code /Prev} of one-byte entries,
   * twenty thousand of them, packed into a few hundred bytes.
   */
  @Test
  void crossReferenceEntriesPastTheObjectLimitAreRefused() throws IOException {
    Path file = tempDir.resolve("w100.pdf");
    RawPdf pdf = onePage();
    byte[] entries = new byte[20_000];
    java.util.Arrays.fill(entries, (byte) 1);
    Files.write(
        file,
        pdf.withPreviousXrefStream(
            "/W [1 0 0] /Index [100 20000] /Filter /FlateDecode", deflated(entries, 0), 1));

    assertThatThrownBy(() -> fewObjects.run(DocumentFormatSource.ofFile(file, "w100.pdf")))
        .isInstanceOf(ParserLimitExceededException.class)
        .hasMessageContaining("10000 objects");
  }

  /** A cross-reference stream counts the entries its data holds, not the ones it declares. */
  @Test
  void aCrossReferenceStreamCountsOnlyTheEntriesItsDataHolds() throws IOException {
    Path file = tempDir.resolve("declared.pdf");
    RawPdf pdf = onePage();
    Files.write(
        file,
        pdf.withPreviousXrefStream("/W [1 0 0] /Index [100 2000000000]", new byte[] {0, 0}, 1));

    DocumentFormatResult result = fewObjects.run(DocumentFormatSource.ofFile(file, "declared.pdf"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
  }

  /**
   * Regression guard: PDFBox parses every object of an object stream once one of them is asked for;
   * twenty thousand empty arrays next to the catalog.
   */
  @Test
  void objectStreamObjectsPastTheObjectLimitAreRefused() throws IOException {
    Path file = tempDir.resolve("objstm-many.pdf");
    StringBuilder header = new StringBuilder("1 0 ");
    StringBuilder objects = new StringBuilder("<< /Type /Catalog /Pages 2 0 R >> ");
    for (int i = 0; i < 20_000; i++) {
      header.append(1_000 + i).append(' ').append(objects.length()).append(' ');
      objects.append("[] ");
    }
    RawPdf pdf = new RawPdf();
    pdf.stream(
        5,
        "/Type /ObjStm /N 20001 /First " + header.length() + " /Filter /FlateDecode",
        deflated(ascii(header + objects.toString()), 0));
    pdf.object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
    pdf.object(3, page(2, 4));
    pdf.stream(4, "", textContent("Text."));
    Files.write(file, pdf.withXrefStream(6, 1, 5, -1));

    assertThatThrownBy(() -> fewObjects.run(DocumentFormatSource.ofFile(file, "objstm-many.pdf")))
        .isInstanceOf(ParserLimitExceededException.class)
        .hasMessageContaining("10000 objects");
  }

  /** Regression guard: the brute-force search registers every object it finds in the file. */
  @Test
  void objectsTheBruteForceSearchFindsCountAgainstTheObjectLimit() throws IOException {
    Path file = tempDir.resolve("broken-many.pdf");
    RawPdf pdf = onePage();
    for (int i = 0; i < 20_000; i++) {
      pdf.object(1_000 + i, "null");
    }
    Files.write(file, pdf.withBrokenXref(1));

    assertThatThrownBy(() -> fewObjects.run(DocumentFormatSource.ofFile(file, "broken-many.pdf")))
        .isInstanceOf(ParserLimitExceededException.class)
        .hasMessageContaining("10000 objects");
  }

  /** Regression guard: a classic cross-reference table of twenty thousand entries. */
  @Test
  void classicCrossReferenceEntriesCountAgainstTheObjectLimit() throws IOException {
    Path file = tempDir.resolve("classic-many.pdf");
    RawPdf pdf = onePage();
    Files.write(file, pdf.withXrefTable(1, 20_000));

    assertThatThrownBy(() -> fewObjects.run(DocumentFormatSource.ofFile(file, "classic-many.pdf")))
        .isInstanceOf(ParserLimitExceededException.class)
        .hasMessageContaining("10000 objects");
  }

  /**
   * Regression guard: PDFBox accepts table lines of six bytes ({@code 9 0 n}), so the length of a
   * table does not bound its entries; they are counted line by line before PDFBox reads them.
   */
  @Test
  void shortClassicCrossReferenceLinesCountAgainstTheObjectLimit() throws IOException {
    Path file = tempDir.resolve("short-lines.pdf");
    RawPdf pdf = onePage();
    Files.write(file, pdf.withXrefTable(1, 20_000, "9 0 n\n"));

    assertThatThrownBy(() -> fewObjects.run(DocumentFormatSource.ofFile(file, "short-lines.pdf")))
        .isInstanceOf(ParserLimitExceededException.class)
        .hasMessageContaining("10000 objects");
  }

  /**
   * Regression guard: an ordinary table of 20-byte entries counts each entry once, so nine thousand
   * of them stay within a limit of ten thousand objects.
   */
  @Test
  void anOrdinaryClassicTableCountsEachEntryOnce() throws IOException {
    Path file = tempDir.resolve("ordinary.pdf");
    RawPdf pdf = onePage();
    Files.write(file, pdf.withXrefTable(1, 9_000));

    DocumentFormatResult result = fewObjects.run(DocumentFormatSource.ofFile(file, "ordinary.pdf"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
  }

  /**
   * Regression guard: PDFBox skips form feeds, NUL bytes and comment lines in front of an entry, so
   * they must not end the count of a table's lines while PDFBox still reads the entries behind
   * them.
   */
  @ParameterizedTest
  @ValueSource(strings = {"\f9 0 n\n", "\u00009 0 n\n", "% x\n9 0 n\n", "+9 0 n\n"})
  void entriesBehindSkippedBytesCountAgainstTheObjectLimit(String line) throws IOException {
    Path file = tempDir.resolve("skipped.pdf");
    RawPdf pdf = onePage();
    Files.write(file, pdf.withXrefTable(1, 20_000, line));

    assertThatThrownBy(() -> fewObjects.run(DocumentFormatSource.ofFile(file, "skipped.pdf")))
        .isInstanceOf(ParserLimitExceededException.class)
        .hasMessageContaining("10000 objects");
  }

  /**
   * Regression guard: the brute-force search registers an object for every {@code obj} it finds
   * before anything is counted; the markers are counted first, here twenty thousand of one object
   * in comments of an otherwise readable file.
   */
  @Test
  void objectMarkersAreCountedBeforeTheBruteForceSearchRuns() throws IOException {
    Path file = tempDir.resolve("markers.pdf");
    RawPdf pdf = onePage();
    pdf.raw("% 9 0 obj\n".repeat(20_000));
    Files.write(file, pdf.withBrokenXref(1));

    assertThatThrownBy(() -> fewObjects.run(DocumentFormatSource.ofFile(file, "markers.pdf")))
        .isInstanceOf(ParserLimitExceededException.class)
        .hasMessageContaining("10000 objects");
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

  /** Catalog 1, page tree 2, page 3 and its content 4. */
  private static RawPdf onePage() {
    RawPdf pdf = new RawPdf();
    pdf.object(1, "<< /Type /Catalog /Pages 2 0 R >>");
    pdf.object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
    pdf.object(3, page(2, 4));
    pdf.stream(4, "", textContent("Eine Seite Text."));
    return pdf;
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
      return withXrefTable(root, 0);
    }

    /**
     * A classic cross-reference table over the objects written so far, followed by a section of
     * {@code extra} entries that all point at the first object.
     */
    byte[] withXrefTable(int root, int extra) {
      return withXrefTable(root, extra, String.format("%010d 00000 n \n", offsetOf(1)));
    }

    /** {@code text} as it is, outside any object. */
    void raw(String text) {
      write(ascii(text));
    }

    /**
     * A classic cross-reference table over the objects written so far, followed by a section of
     * {@code extra} copies of {@code line}.
     */
    byte[] withXrefTable(int root, int extra, String line) {
      int size = maxNumber() + 1;
      long start = body.size();
      StringBuilder table = new StringBuilder("xref\n0 " + size + "\n0000000000 65535 f \n");
      for (int n = 1; n < size; n++) {
        long offset = offsetOf(n);
        table.append(
            offset < 0 ? "0000000000 65535 f \n" : String.format("%010d 00000 n \n", offset));
      }
      if (extra > 0) {
        table.append(size).append(' ').append(extra).append('\n');
        table.append(line.repeat(extra));
        size += extra;
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
      return withXrefStream(number, root, objectStream, padding, "");
    }

    /**
     * An older cross-reference stream with {@code entries} and {@code data}, then a current one
     * over the objects written so far that names it as {@code /Prev}.
     */
    byte[] withPreviousXrefStream(String entries, byte[] data, int root) throws IOException {
      int previous = maxNumber() + 1;
      long previousAt = body.size();
      offsets.add(new long[] {previous, previousAt});
      write(
          ascii(
              previous
                  + " 0 obj\n<< /Type /XRef "
                  + entries
                  + " /Length "
                  + data.length
                  + " >>\nstream\n"));
      write(data);
      write(ascii("\nendstream\nendobj\n"));
      return withXrefStream(previous + 1, root, -1, -1, " /Prev " + previousAt);
    }

    private byte[] withXrefStream(
        int number, int root, int objectStream, int padding, String trailerEntries)
        throws IOException {
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
                  + trailerEntries
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
