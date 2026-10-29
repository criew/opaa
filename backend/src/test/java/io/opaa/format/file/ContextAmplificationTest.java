package io.opaa.format.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.chunk.ChunkMetadataKeys;
import io.opaa.format.file.markdown.MarkdownDocumentFormat;
import io.opaa.format.file.tabular.TabularDocumentFormat;
import io.opaa.format.file.tabular.TabularProperties;
import io.opaa.format.shared.HeadingSectionSplitterFixture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

/**
 * Context a chunk repeats - the heading line in front of every chunk of a section and in its
 * Fundort, a table's context and header line in front of every row group - is cut short, so a long
 * heading or a wide header cannot multiply itself by the number of chunks; and a spreadsheet's
 * expanded cells count against the text limit while they are read.
 */
class ContextAmplificationTest {

  @TempDir Path tempDir;

  /**
   * Regression guard: one heading of 20 000 characters over 300 sections of their own repeated that
   * heading in every chunk and every Fundort, 6 million characters from 150 kilobytes.
   */
  @Test
  void aLongHeadingIsNotRepeatedInFullInEveryChunk() throws IOException {
    StringBuilder markdown = new StringBuilder("# ").append("Ü".repeat(20_000)).append("\n\n");
    for (int i = 0; i < 300; i++) {
      markdown
          .append("## Abschnitt ")
          .append(i)
          .append("\n\n")
          .append("Der Abschnitt regelt einen eigenen Sachverhalt mit genügend Text. ".repeat(4))
          .append("\n\n");
    }
    Path file = tempDir.resolve("lang.md");
    Files.writeString(file, markdown);

    DocumentFormatResult result =
        new MarkdownDocumentFormat(HeadingSectionSplitterFixture.DEFAULT)
            .run(DocumentFormatSource.ofFile(file, "lang.md"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(totalCharacters(result)).isLessThan(5L * markdown.length());
    for (Document chunk : result.chunks()) {
      assertThat((String) chunk.getMetadata().get(ChunkMetadataKeys.LOCATION_METADATA_KEY))
          .hasSizeLessThanOrEqualTo(600);
    }
  }

  /**
   * Regression guard: a header line of 20 000 characters stood in front of every group of rows,
   * which for short rows is a group of a few lines.
   */
  @Test
  void aWideHeaderLineIsNotRepeatedInFullInEveryRowGroup() throws IOException {
    StringBuilder csv = new StringBuilder();
    for (int column = 0; column < 50; column++) {
      csv.append(column == 0 ? "" : ",").append("Spalte").append(column).append("x".repeat(400));
    }
    csv.append('\n');
    for (int row = 0; row < 1_000; row++) {
      csv.append("1,".repeat(49)).append("1\n");
    }
    Path file = tempDir.resolve("breit.csv");
    Files.writeString(file, csv);

    DocumentFormatResult result =
        new TabularDocumentFormat(
                new TabularProperties(0, 0, 0, 0), HeadingSectionSplitterFixture.DEFAULT)
            .run(DocumentFormatSource.ofFile(file, "breit.csv"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    // the rows render to about twice their CSV size, and the context takes no more than the body
    assertThat(totalCharacters(result)).isLessThan(4L * csv.length());
  }

  /**
   * Regression guard: a header of sixty ordinary column names, under half the target size, is
   * repeated in full in every row group.
   */
  @Test
  void anOrdinaryWideHeaderKeepsEveryColumnName() throws IOException {
    StringBuilder csv = new StringBuilder();
    for (int column = 0; column < 60; column++) {
      csv.append(column == 0 ? "" : ";").append("Spaltenname_").append(column);
    }
    csv.append('\n');
    for (int row = 0; row < 200; row++) {
      for (int column = 0; column < 60; column++) {
        csv.append(column == 0 ? "" : ";").append('w').append(row).append('_').append(column);
      }
      csv.append('\n');
    }
    Path file = tempDir.resolve("sechzig.csv");
    Files.writeString(file, csv);

    DocumentFormatResult result = tabular().run(DocumentFormatSource.ofFile(file, "sechzig.csv"));

    assertThat(result.chunks()).hasSizeGreaterThan(1);
    assertThat(result.chunks())
        .allSatisfy(chunk -> assertThat(chunk.getText()).contains("Spaltenname_59"));
  }

  /** A header too long to repeat in full is indexed in full once, in a chunk of its own. */
  @Test
  void aHeaderCutShortIsIndexedInFullOnce() throws IOException {
    StringBuilder csv = new StringBuilder();
    for (int column = 0; column < 50; column++) {
      csv.append(column == 0 ? "" : ",").append("Spalte").append(column).append("x".repeat(400));
    }
    csv.append('\n');
    for (int row = 0; row < 100; row++) {
      csv.append("1,".repeat(49)).append("1\n");
    }
    Path file = tempDir.resolve("kopf.csv");
    Files.writeString(file, csv);

    DocumentFormatResult result = tabular().run(DocumentFormatSource.ofFile(file, "kopf.csv"));

    assertThat(String.join("\n", result.chunks().stream().map(Document::getText).toList()))
        .contains("Spalte49");
    assertThat(result.chunks())
        .filteredOn(chunk -> chunk.getText().contains("1 | 1 | 1"))
        .allSatisfy(chunk -> assertThat(chunk.getText()).doesNotContain("Spalte49"));
  }

  private static TabularDocumentFormat tabular() {
    return new TabularDocumentFormat(
        new TabularProperties(0, 0, 0, 0), HeadingSectionSplitterFixture.DEFAULT);
  }

  /**
   * Regression guard: a cell of 5 000 characters repeated across its row by {@code
   * number-columns-repeated}, fifty rows of it, expands to 12.5 million characters from a quarter
   * megabyte; the expansion counts against the text limit while the sheet is read, not after.
   */
  @Test
  void expandedOdsCellsCountAgainstTheTextLimitWhileReading() throws IOException {
    String cell =
        "<table:table-cell table:number-columns-repeated=\"50\"><text:p>"
            + "w".repeat(5_000)
            + "</text:p></table:table-cell>";
    StringBuilder rows = new StringBuilder();
    for (int i = 0; i < 50; i++) {
      rows.append("<table:table-row>").append(cell).append("</table:table-row>");
    }
    Path file = tempDir.resolve("wiederholt.ods");
    writeOds(file, "<table:table table:name=\"Blatt\">" + rows + "</table:table>");

    Throwable thrown =
        catchThrowable(
            () ->
                new TabularDocumentFormat(
                        new TabularProperties(0, 0, 0, 0), HeadingSectionSplitterFixture.DEFAULT)
                    .run(DocumentFormatSource.ofFile(file, "wiederholt.ods")));

    assertThat(thrown).isNotNull();
    assertThat(messages(thrown)).contains("ODS spreadsheet exceeds");
  }

  private static long totalCharacters(DocumentFormatResult result) {
    long total = 0;
    for (Document chunk : result.chunks()) {
      total += chunk.getText().length();
      Object location = chunk.getMetadata().get(ChunkMetadataKeys.LOCATION_METADATA_KEY);
      total += location instanceof String text ? text.length() : 0;
    }
    return total;
  }

  private static String messages(Throwable thrown) {
    StringBuilder all = new StringBuilder();
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      all.append(t.getMessage()).append('\n');
    }
    return all.toString();
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
      out.putNextEntry(new ZipEntry("mimetype"));
      out.write(
          "application/vnd.oasis.opendocument.spreadsheet".getBytes(StandardCharsets.US_ASCII));
      out.closeEntry();
      out.putNextEntry(new ZipEntry("content.xml"));
      out.write(content.getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
  }
}
