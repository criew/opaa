package io.opaa.indexing.pipeline.office;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDecimalNumber;

/**
 * The DOCX pipeline (#1061; ingestion-pipelines.md Teil 2): the cut follows heading levels taken
 * from a paragraph's own formatting (built-in style or direct outline level), mirroring
 * MarkdownDocumentPipelineTest/HtmlDocumentPipelineTest for the same section-building rules.
 */
class DocxDocumentPipelineTest {

  @TempDir Path tempDir;

  private final DocxDocumentPipeline pipeline = new DocxDocumentPipeline();

  @Test
  void claimsExactlyDocx() {
    assertThat(pipeline.handledFormats()).containsExactly(".docx");
    assertThat(pipeline.id()).isEqualTo("docx");
    assertThat(pipeline.version()).isEqualTo((short) 1);
  }

  @Test
  void cutsFollowHeadingStylesWithOneChunkPerSectionAndTheHeadingInTheChunkText()
      throws IOException {
    Path file = tempDir.resolve("satzung.docx");
    try (XWPFDocument doc = new XWPFDocument()) {
      addHeading(doc, "Verwaltungsgebuehrensatzung", "Heading1");
      addParagraph(doc, "Diese Satzung regelt die Gebuehren der Stadt.");
      addHeading(doc, "Personaldokumente", "Heading2");
      addParagraph(doc, "Fuer die Ausstellung eines Personalausweises werden Gebuehren erhoben.");
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "satzung.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().get(0).getText())
        .startsWith("Verwaltungsgebuehrensatzung")
        .contains("regelt die Gebuehren");
    assertThat(result.chunks().get(0).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Abschn. Verwaltungsgebuehrensatzung");
    assertThat(result.chunks().get(1).getText())
        .startsWith("Verwaltungsgebuehrensatzung › Personaldokumente")
        .contains("Personalausweises");
    // The keys actually produced must stay within what the pipeline declares - the guard
    // DocumentPipelineRegistryRoutingIntegrationTest#everyPipelineDeclaresExactlyItsOwnMetadataKeys
    // checks the declaration; this checks the declaration against real output.
    Set<String> actualKeys =
        result.chunks().stream().flatMap(c -> c.getMetadata().keySet().stream()).collect(toSet());
    assertThat(pipeline.passthroughMetadataKeys()).containsAll(actualKeys);
  }

  @Test
  void aDirectOutlineLevelIsRecognizedWithoutAHeadingStyle() throws IOException {
    Path file = tempDir.resolve("gliederung.docx");
    try (XWPFDocument doc = new XWPFDocument()) {
      addHeadingByOutlineLevel(doc, "Kapitel eins", 0);
      addParagraph(doc, "Text im ersten Kapitel.");
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "gliederung.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText())
        .startsWith("Kapitel eins")
        .contains("Text im ersten Kapitel");
  }

  @Test
  void aTableIsReadCellByCellAndAddedAsBodyText() throws IOException {
    // Gebührenverzeichnisse und Formulare sind praktisch immer Tabellen (#1104 review, wichtig 2)
    // - getBodyElements() must not silently drop them the way getParagraphs() alone would.
    Path file = tempDir.resolve("gebuehrenverzeichnis.docx");
    try (XWPFDocument doc = new XWPFDocument()) {
      addHeading(doc, "Gebuehrenverzeichnis", "Heading1");
      XWPFTable table = doc.createTable(2, 2);
      table.getRow(0).getCell(0).setText("Leistung");
      table.getRow(0).getCell(1).setText("Gebuehr");
      table.getRow(1).getCell(0).setText("Personalausweis");
      table.getRow(1).getCell(1).setText("37,00 EUR");
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "gebuehrenverzeichnis.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText())
        .startsWith("Gebuehrenverzeichnis")
        .contains("Leistung | Gebuehr")
        .contains("Personalausweis | 37,00 EUR");
  }

  @Test
  void aGermanStyleIdIsRecognizedAsAHeading() throws IOException {
    // #1104 review, Nit 5: the styleId is not reliably English regardless of authoring locale -
    // LibreOffice/some German Word templates export "berschrift1" ("Ü" stripped by the OOXML
    // styleId sanitizer).
    Path file = tempDir.resolve("deutsche-formatvorlage.docx");
    try (XWPFDocument doc = new XWPFDocument()) {
      addHeading(doc, "Erster Abschnitt", "berschrift1");
      addParagraph(doc, "Text im ersten Abschnitt.");
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "deutsche-formatvorlage.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText())
        .startsWith("Erster Abschnitt")
        .contains("Text im ersten Abschnitt");
  }

  @Test
  void aFilelessSourceHasNoContent() {
    // A DOCX pipeline is only ever reached through a genuine .docx file (never RSS-extracted
    // text, ADR-0017 decision 2) - defensive fallback, mirrors PdfDocumentPipeline/
    // PptxDocumentPipeline.
    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofExtractedText("irrelevanter Text", "quelle.docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void aHeadingWithNoBodyTextStillBecomesItsOwnChunkInsteadOfNoExtractableText()
      throws IOException {
    Path file = tempDir.resolve("nur-ueberschrift.docx");
    try (XWPFDocument doc = new XWPFDocument()) {
      addHeading(doc, "Nur eine Ueberschrift", "Heading1");
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "nur-ueberschrift.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).isEqualTo("Nur eine Ueberschrift");
  }

  @Test
  void aDocumentWithoutAnyTextHasNoContent() throws IOException {
    Path file = tempDir.resolve("leer.docx");
    try (XWPFDocument doc = new XWPFDocument()) {
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "leer.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void aFileThatIsNotAValidDocxHasNoContent() throws IOException {
    Path file = tempDir.resolve("kaputt.docx");
    Files.writeString(file, "das ist kein docx");

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "kaputt.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
  }

  private static void addHeading(XWPFDocument doc, String text, String styleId) {
    XWPFParagraph paragraph = doc.createParagraph();
    paragraph.setStyle(styleId);
    paragraph.createRun().setText(text);
  }

  private static void addHeadingByOutlineLevel(XWPFDocument doc, String text, int outlineLevel) {
    XWPFParagraph paragraph = doc.createParagraph();
    var pPr =
        paragraph.getCTP().isSetPPr()
            ? paragraph.getCTP().getPPr()
            : paragraph.getCTP().addNewPPr();
    CTDecimalNumber outlineLvl = pPr.addNewOutlineLvl();
    outlineLvl.setVal(BigInteger.valueOf(outlineLevel));
    paragraph.createRun().setText(text);
  }

  private static void addParagraph(XWPFDocument doc, String text) {
    doc.createParagraph().createRun().setText(text);
  }

  private static void write(XWPFDocument doc, Path file) throws IOException {
    try (OutputStream out = Files.newOutputStream(file)) {
      doc.write(out);
    }
  }
}
