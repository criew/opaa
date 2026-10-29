package io.opaa.format.file;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.format.DocumentFormat;
import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatRunner;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.file.html.HtmlDocumentFormat;
import io.opaa.format.file.markdown.MarkdownDocumentFormat;
import io.opaa.format.file.office.DocxDocumentFormat;
import io.opaa.format.file.tabular.TabularDocumentFormat;
import io.opaa.format.file.tabular.TabularProperties;
import io.opaa.format.shared.HeadingSectionSplitterFixture;
import io.opaa.format.shared.ParserLimitProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every structure format refuses a document whose chunks carry more text than {@link
 * ParserLimitProperties#maxTextCharacters()}, the same ceiling the ODF formats apply to their own
 * text, so a document cannot cost unbounded embedding work.
 */
class StructureFormatTextLimitTest {

  private static final ParserLimitProperties LIMIT =
      new ParserLimitProperties(2_000, 0, 0, 0, 0, 0, 0, 0);

  /** About 6 000 characters in sentences, cut into several chunks. */
  private static final String LONG_TEXT =
      "Die Gebuehr fuer einen Personalausweis betraegt 37 Euro und wird bei Antrag faellig. "
          .repeat(70);

  @TempDir Path tempDir;

  @Test
  void markdownPastTheLimitFails() throws IOException {
    Path file = write("lang.md", "# Gebuehren\n\n" + LONG_TEXT);

    assertThat(run(new MarkdownDocumentFormat(HeadingSectionSplitterFixture.DEFAULT, LIMIT), file))
        .isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  @Test
  void markdownWithinTheLimitIsRead() throws IOException {
    Path file = write("kurz.md", "# Gebuehren\n\nDie Gebuehr betraegt 37 Euro.");

    assertThat(run(new MarkdownDocumentFormat(HeadingSectionSplitterFixture.DEFAULT, LIMIT), file))
        .isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
  }

  @Test
  void htmlPastTheLimitFails() throws IOException {
    Path file =
        write("lang.html", "<html><body><h1>Gebuehren</h1><p>" + LONG_TEXT + "</p></body></html>");

    assertThat(run(new HtmlDocumentFormat(HeadingSectionSplitterFixture.DEFAULT, LIMIT), file))
        .isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  @Test
  void csvPastTheLimitFails() throws IOException {
    StringBuilder csv = new StringBuilder("Leistung;Gebuehr\n");
    for (int i = 0; i < 300; i++) {
      csv.append("Personalausweis Nummer ").append(i).append(";37 Euro\n");
    }
    Path file = write("lang.csv", csv.toString());
    var tabular =
        new TabularDocumentFormat(
            new TabularProperties(0, 0, 0, 0), HeadingSectionSplitterFixture.DEFAULT, LIMIT);

    assertThat(run(tabular, file)).isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  @Test
  void docxPastTheLimitFails() throws IOException {
    Path file = tempDir.resolve("lang.docx");
    try (XWPFDocument document = new XWPFDocument();
        OutputStream out = Files.newOutputStream(file)) {
      for (int i = 0; i < 70; i++) {
        document
            .createParagraph()
            .createRun()
            .setText("Die Gebuehr fuer einen Personalausweis betraegt 37 Euro. Absatz " + i);
      }
      document.write(out);
    }

    assertThat(run(new DocxDocumentFormat(HeadingSectionSplitterFixture.DEFAULT, LIMIT), file))
        .isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  private Path write(String name, String content) throws IOException {
    Path file = tempDir.resolve(name);
    Files.writeString(file, content);
    return file;
  }

  private static DocumentFormatResult.Outcome run(DocumentFormat format, Path file) {
    return DocumentFormatRunner.run(
            format, DocumentFormatSource.ofFile(file, file.getFileName().toString()))
        .outcome();
  }
}
