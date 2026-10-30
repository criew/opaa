package io.opaa.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Another compression of the same content is no difference; another content is, named by file and
 * entry. A different deflate level stands in for a different zlib.
 */
class SeedComparisonTest {

  private static final Path CORPUS = FuzzCorpus.repositoryRoot().resolve(FuzzCorpus.CORPUS);

  @Test
  void aPackageCompressedOtherwiseHasNoDifference() throws IOException {
    byte[] committed = Files.readAllBytes(CORPUS.resolve("docx-paragraph-flood.docx"));
    byte[] recompressed = rezipped(committed, Deflater.BEST_SPEED, null, null);

    assertThat(recompressed).isNotEqualTo(committed);
    assertThat(SeedComparison.difference("docx-paragraph-flood.docx", committed, recompressed))
        .isEmpty();
  }

  @Test
  void aChangedEntryIsNamedWithItsFile() throws IOException {
    byte[] committed = Files.readAllBytes(CORPUS.resolve("xlsx-text.xlsx"));
    byte[] changed = rezipped(committed, Deflater.DEFAULT_COMPRESSION, "docProps/app.xml", "x");

    assertThat(SeedComparison.difference("xlsx-text.xlsx", committed, changed))
        .hasValueSatisfying(
            message -> assertThat(message).startsWith("xlsx-text.xlsx!docProps/app.xml: "));
  }

  @Test
  void aPdfWhoseStreamIsCompressedOtherwiseHasNoDifference() throws IOException {
    byte[] content =
        "BT /F1 12 Tf 72 720 Td (a) Tj ET\n".repeat(4_000).getBytes(StandardCharsets.ISO_8859_1);
    byte[] best = pdf(deflated(content, Deflater.BEST_COMPRESSION));
    byte[] fast = pdf(deflated(content, Deflater.BEST_SPEED));

    assertThat(fast).isNotEqualTo(best);
    assertThat(SeedComparison.difference("run.pdf", best, fast)).isEmpty();
  }

  @Test
  void aPdfWhoseStreamSaysSomethingElseIsADifference() throws IOException {
    byte[] content = "BT (a) Tj ET\n".repeat(100).getBytes(StandardCharsets.ISO_8859_1);
    byte[] other = "BT (b) Tj ET\n".repeat(100).getBytes(StandardCharsets.ISO_8859_1);

    assertThat(
            SeedComparison.difference(
                "run.pdf",
                pdf(deflated(content, Deflater.BEST_COMPRESSION)),
                pdf(deflated(other, Deflater.BEST_COMPRESSION))))
        .hasValueSatisfying(message -> assertThat(message).startsWith("run.pdf (PDF"));
  }

  @Test
  void anyOtherFileIsComparedByteForByte() {
    assertThat(SeedComparison.difference("a.txt", "abc".getBytes(), "abd".getBytes()))
        .hasValue("a.txt: first difference at byte 2 (expected 3 bytes, generated 3)");
  }

  /** {@code zipped} written again at {@code level}, with {@code changedEntry} replaced if given. */
  private static byte[] rezipped(byte[] zipped, int level, String changedEntry, String text)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zipped));
        ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.setLevel(level);
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        byte[] data = in.readAllBytes();
        if (entry.getName().equals(changedEntry)) {
          data = text.getBytes(StandardCharsets.UTF_8);
        }
        ZipEntry copy = new ZipEntry(entry.getName());
        copy.setTimeLocal(entry.getTimeLocal());
        zip.putNextEntry(copy);
        zip.write(data);
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

  /** A one-object PDF around {@code stream}, with its length, xref offset and startxref. */
  private static byte[] pdf(byte[] stream) {
    String head = "%PDF-1.7\n";
    String object = "1 0 obj\n<< /Filter /FlateDecode /Length " + stream.length + " >>\nstream\n";
    String tail = "\nendstream\nendobj\n";
    int xref = head.length() + object.length() + stream.length + tail.length();
    String trailer =
        "xref\n0 2\n0000000000 65535 f \n"
            + String.format("%010d", head.length())
            + " 00000 n \ntrailer\n<< /Size 2 >>\nstartxref\n"
            + xref
            + "\n%%EOF\n";
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes((head + object).getBytes(StandardCharsets.ISO_8859_1));
    out.writeBytes(stream);
    out.writeBytes((tail + trailer).getBytes(StandardCharsets.ISO_8859_1));
    return out.toByteArray();
  }
}
