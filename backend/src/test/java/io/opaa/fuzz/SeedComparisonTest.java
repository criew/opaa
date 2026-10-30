package io.opaa.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Another compression of the same content is no difference; another content, entry time, method or
 * order, or an offset that no longer points where it should, is - named by file and entry. A
 * different deflate level stands in for a different zlib.
 */
class SeedComparisonTest {

  private static final Path CORPUS = FuzzCorpus.repositoryRoot().resolve(FuzzCorpus.CORPUS);
  private static final byte[] CONTENT = latin1("BT /F1 12 Tf 72 720 Td (a) Tj ET\n".repeat(4_000));
  private static final String FLATE = "/Filter /FlateDecode ";

  // --- ZIP ---------------------------------------------------------------------------------------

  @Test
  void aPackageCompressedOtherwiseHasNoDifference() throws IOException {
    byte[] committed = committed("docx-paragraph-flood.docx");
    byte[] recompressed = rezipped(committed, Deflater.BEST_SPEED, UnaryOperator.identity());

    assertThat(recompressed).isNotEqualTo(committed);
    assertThat(SeedComparison.difference("docx-paragraph-flood.docx", committed, recompressed))
        .isEmpty();
  }

  @Test
  void aChangedEntryIsNamedWithItsFile() throws IOException {
    byte[] committed = committed("xlsx-text.xlsx");
    byte[] changed =
        rezipped(
            committed,
            Deflater.DEFAULT_COMPRESSION,
            parts -> replaced(parts, "docProps/app.xml", p -> p.withData(latin1("x"))));

    assertThat(SeedComparison.difference("xlsx-text.xlsx", committed, changed))
        .hasValueSatisfying(
            message -> assertThat(message).startsWith("xlsx-text.xlsx!docProps/app.xml: "));
  }

  @Test
  void aChangedEntryTimeIsADifference() throws IOException {
    byte[] committed = committed("xlsx-text.xlsx");
    byte[] changed =
        rezipped(
            committed,
            Deflater.DEFAULT_COMPRESSION,
            parts ->
                replaced(
                    parts,
                    "docProps/app.xml",
                    p -> p.withTime(LocalDateTime.of(2020, 5, 5, 10, 0))));

    assertThat(SeedComparison.difference("xlsx-text.xlsx", committed, changed))
        .hasValueSatisfying(
            message -> assertThat(message).startsWith("xlsx-text.xlsx!docProps/app.xml: time "));
  }

  @Test
  void aChangedEntryMethodIsADifference() throws IOException {
    byte[] committed = committed("xlsx-text.xlsx");
    byte[] changed =
        rezipped(
            committed,
            Deflater.DEFAULT_COMPRESSION,
            parts -> replaced(parts, "docProps/app.xml", p -> p.withMethod(ZipEntry.STORED)));

    assertThat(SeedComparison.difference("xlsx-text.xlsx", committed, changed))
        .hasValueSatisfying(
            message -> assertThat(message).startsWith("xlsx-text.xlsx!docProps/app.xml: method "));
  }

  @Test
  void aChangedEntryOrderIsADifference() throws IOException {
    byte[] committed = committed("xlsx-text.xlsx");
    byte[] changed =
        rezipped(
            committed,
            Deflater.DEFAULT_COMPRESSION,
            parts -> {
              List<Part> swapped = new ArrayList<>(parts);
              Collections.swap(swapped, 0, 1);
              return swapped;
            });

    assertThat(SeedComparison.difference("xlsx-text.xlsx", committed, changed))
        .hasValueSatisfying(message -> assertThat(message).startsWith("xlsx-text.xlsx: entry 0 "));
  }

  // --- PDF with Flate ----------------------------------------------------------------------------

  @Test
  void aPdfWhoseStreamIsCompressedOtherwiseHasNoDifference() throws IOException {
    byte[] best = pdf(FLATE, deflated(CONTENT, Deflater.BEST_COMPRESSION));
    byte[] fast = pdf(FLATE, deflated(CONTENT, Deflater.BEST_SPEED));

    assertThat(fast).isNotEqualTo(best);
    assertThat(SeedComparison.difference("run.pdf", best, fast)).isEmpty();
  }

  @Test
  void aPdfWhoseStreamSaysSomethingElseIsADifference() throws IOException {
    byte[] other = latin1("BT /F1 12 Tf 72 720 Td (b) Tj ET\n".repeat(4_000));

    assertThat(
            SeedComparison.difference(
                "run.pdf",
                pdf(FLATE, deflated(CONTENT, Deflater.BEST_COMPRESSION)),
                pdf(FLATE, deflated(other, Deflater.BEST_COMPRESSION))))
        .hasValueSatisfying(message -> assertThat(message).startsWith("run.pdf (PDF"));
  }

  @Test
  void aStartxrefThatNoLongerPointsToTheXrefIsADifference() throws IOException {
    byte[] pdf = pdf(FLATE, deflated(CONTENT, Deflater.BEST_COMPRESSION));
    byte[] moved = latin1(latin1(pdf).replaceFirst("startxref\n\\d+", "startxref\n9"));

    assertThat(SeedComparison.difference("run.pdf", pdf, moved)).isPresent();
  }

  @Test
  void anXrefOffsetThatNoLongerPointsToItsObjectIsADifference() throws IOException {
    byte[] pdf = pdf(FLATE, deflated(CONTENT, Deflater.BEST_COMPRESSION));
    byte[] moved = latin1(latin1(pdf).replace("0000000009 00000 n", "0000000010 00000 n"));

    assertThat(SeedComparison.difference("run.pdf", pdf, moved)).isPresent();
  }

  @Test
  void bytesAfterTheDeflateEndAreCompared() throws IOException {
    byte[] stream = deflated(CONTENT, Deflater.BEST_COMPRESSION);
    byte[] withTail = concat(stream, latin1("tail"));

    assertThat(SeedComparison.difference("run.pdf", pdf(FLATE, stream), pdf(FLATE, withTail)))
        .isPresent();
  }

  @Test
  void aStreamThatDoesNotInflateIsComparedRaw() {
    byte[] one = pdf(FLATE, latin1("not deflated, one"));
    byte[] other = pdf(FLATE, latin1("not deflated, two"));

    assertThat(SeedComparison.difference("run.pdf", one, one.clone())).isEmpty();
    assertThat(SeedComparison.difference("run.pdf", one, other)).isPresent();
  }

  // --- PDF without Flate -------------------------------------------------------------------------

  @Test
  void aPdfWithoutFlateIsComparedByteForByteIncludingItsOffsets() throws IOException {
    byte[] pdf = pdf("", CONTENT);
    byte[] startxref = latin1(latin1(pdf).replaceFirst("startxref\n\\d+", "startxref\n9"));
    byte[] offset = latin1(latin1(pdf).replace("0000000009 00000 n", "0000000010 00000 n"));

    assertThat(SeedComparison.difference("plain.pdf", pdf, startxref)).isPresent();
    assertThat(SeedComparison.difference("plain.pdf", pdf, offset)).isPresent();
  }

  @Test
  void theCraftedStartxrefOfTheCorpusIsHeld() throws IOException {
    byte[] committed = committed("pdf-object-markers-without-xref.pdf");
    byte[] changed = latin1(latin1(committed).replace("startxref\n999999", "startxref\n9"));

    assertThat(changed).isNotEqualTo(committed);
    assertThat(SeedComparison.difference("pdf-object-markers-without-xref.pdf", committed, changed))
        .isPresent();
  }

  @Test
  void anyOtherFileIsComparedByteForByte() {
    assertThat(SeedComparison.difference("a.txt", latin1("abc"), latin1("abd")))
        .hasValue("a.txt: first difference at byte 2 (expected 3 bytes, generated 3)");
  }

  // --- Helpers -----------------------------------------------------------------------------------

  private record Part(String name, int method, LocalDateTime time, byte[] data) {
    Part withData(byte[] other) {
      return new Part(name, method, time, other);
    }

    Part withTime(LocalDateTime other) {
      return new Part(name, method, other, data);
    }

    Part withMethod(int other) {
      return new Part(name, other, time, data);
    }
  }

  private static byte[] concat(byte[] a, byte[] b) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(a);
    out.writeBytes(b);
    return out.toByteArray();
  }

  private static byte[] committed(String name) throws IOException {
    return Files.readAllBytes(CORPUS.resolve(name));
  }

  private static List<Part> replaced(List<Part> parts, String name, UnaryOperator<Part> change) {
    return parts.stream().map(p -> p.name().equals(name) ? change.apply(p) : p).toList();
  }

  /** {@code zipped} written again at {@code level}, its parts changed by {@code change}. */
  private static byte[] rezipped(byte[] zipped, int level, UnaryOperator<List<Part>> change)
      throws IOException {
    List<Part> parts = new ArrayList<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zipped))) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        parts.add(
            new Part(entry.getName(), entry.getMethod(), entry.getTimeLocal(), in.readAllBytes()));
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.setLevel(level);
      for (Part part : change.apply(parts)) {
        ZipEntry entry = new ZipEntry(part.name());
        entry.setTimeLocal(part.time());
        entry.setMethod(part.method());
        if (part.method() == ZipEntry.STORED) {
          CRC32 crc = new CRC32();
          crc.update(part.data());
          entry.setSize(part.data().length);
          entry.setCompressedSize(part.data().length);
          entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry);
        zip.write(part.data());
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }

  private static byte[] deflated(byte[] data, int level) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (DeflaterOutputStream deflater = new DeflaterOutputStream(out, new Deflater(level))) {
      deflater.write(data);
    }
    return out.toByteArray();
  }

  /**
   * A one-object PDF around {@code stream} with dictionary {@code entries}, its length, an xref
   * entry pointing to the object at offset 9 and a startxref pointing to the xref table.
   */
  private static byte[] pdf(String entries, byte[] stream) {
    String head = "%PDF-1.7\n";
    String object = "1 0 obj\n<< " + entries + "/Length " + stream.length + " >>\nstream\n";
    String tail = "\nendstream\nendobj\n";
    int xref = head.length() + object.length() + stream.length + tail.length();
    String trailer =
        "xref\n0 2\n0000000000 65535 f \n"
            + String.format("%010d", head.length())
            + " 00000 n \ntrailer\n<< /Size 2 >>\nstartxref\n"
            + xref
            + "\n%%EOF\n";
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(latin1(head + object));
    out.writeBytes(stream);
    out.writeBytes(latin1(tail + trailer));
    return out.toByteArray();
  }

  private static byte[] latin1(String text) {
    return text.getBytes(StandardCharsets.ISO_8859_1);
  }

  private static String latin1(byte[] bytes) {
    return new String(bytes, StandardCharsets.ISO_8859_1);
  }
}
