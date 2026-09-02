package io.opaa.indexing.pipeline.office;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.PassthroughMetadataKeysTestSupport;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDecimalNumber;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;

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
    assertThat(pipeline.version()).isEqualTo((short) 2);
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
    // A key this pipeline actually produced that also belongs to the registry-wide passthrough
    // union must be part of its own declaration - storeChunks copies any union key it finds on a
    // chunk regardless of which pipeline declares it (nested-pipeline attribution), so an
    // undeclared union key here would silently ride along. A key outside the union is irrelevant:
    // storeChunks never copies it, declared or not.
    Set<String> actualKeysInUnion =
        result.chunks().stream()
            .flatMap(c -> c.getMetadata().keySet().stream())
            .filter(PassthroughMetadataKeysTestSupport.REGISTRY_UNION::contains)
            .collect(toSet());
    assertThat(pipeline.passthroughMetadataKeys()).containsAll(actualKeysInUnion);
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
  void headerAndFooterTextBecomeOneDeduplicatedLeadingChunk() throws IOException {
    // regression guard for #1145: an authority name/Aktenzeichen placed exclusively in the
    // Kopf-/Fußzeile must still be lexically searchable, and only once - not per page and not
    // dropped, as it always was for DOCX (getBodyElements() never carried it).
    Path file = tempDir.resolve("mit-kopfzeile.docx");
    try (XWPFDocument doc = new XWPFDocument()) {
      XWPFHeaderFooterPolicy policy = doc.createHeaderFooterPolicy();
      XWPFHeader header = policy.createHeader(XWPFHeaderFooterPolicy.DEFAULT);
      header.createParagraph().createRun().setText("Stadt Musterstadt");
      XWPFFooter footer = policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
      footer.createParagraph().createRun().setText("Az. 12-34/2026");
      addHeading(doc, "Antrag", "Heading1");
      addParagraph(doc, "Fachlicher Inhalt des Antrags.");
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "mit-kopfzeile.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().getFirst().getText())
        .contains("Stadt Musterstadt")
        .contains("Az. 12-34/2026");
    assertThat(result.chunks().getFirst().getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Kopf-/Fußzeile");
    assertThat(result.chunks().get(1).getText()).startsWith("Antrag");
  }

  @Test
  void aDocumentWithOnlyHeaderTextAndNoBodyIsStillChunked() throws IOException {
    Path file = tempDir.resolve("nur-kopfzeile.docx");
    try (XWPFDocument doc = new XWPFDocument()) {
      XWPFHeaderFooterPolicy policy = doc.createHeaderFooterPolicy();
      XWPFHeader header = policy.createHeader(XWPFHeaderFooterPolicy.DEFAULT);
      header.createParagraph().createRun().setText("Stadt Musterstadt");
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "nur-kopfzeile.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).isEqualTo("Stadt Musterstadt");
  }

  @Test
  void identicalHeaderVariantsContributeOnlyOnce() throws IOException {
    // regression guard for #1145 review, B2: reading every header/footer part (not just the
    // default one, see aFirstPageOnlyHeaderIsStillIndexed below) must not duplicate text that
    // several variants happen to share verbatim.
    Path file = tempDir.resolve("kopfzeile-varianten.docx");
    try (XWPFDocument doc = new XWPFDocument()) {
      XWPFHeaderFooterPolicy policy = doc.createHeaderFooterPolicy();
      policy
          .createHeader(XWPFHeaderFooterPolicy.DEFAULT)
          .createParagraph()
          .createRun()
          .setText("Stadt Musterstadt");
      policy
          .createHeader(XWPFHeaderFooterPolicy.EVEN)
          .createParagraph()
          .createRun()
          .setText("Stadt Musterstadt");
      addParagraph(doc, "Fachlicher Inhalt.");
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "kopfzeile-varianten.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    long occurrences =
        result.chunks().getFirst().getText().split("Stadt Musterstadt", -1).length - 1;
    assertThat(occurrences).isEqualTo(1);
  }

  @Test
  void aFirstPageOnlyHeaderIsStillIndexed() throws IOException {
    // regression guard for #1145 review, B4: "Erste Seite anders" (w:titlePg) is the common
    // German-authority-letterhead layout - the letterhead lives exclusively in the FIRST header
    // part, never in DEFAULT. An earlier version of this pipeline read only
    // XWPFHeaderFooterPolicy#getDefaultHeader() and therefore missed exactly the case #1145 was
    // filed to fix.
    Path file = tempDir.resolve("nur-erste-seite.docx");
    try (XWPFDocument doc = new XWPFDocument()) {
      XWPFHeaderFooterPolicy policy = doc.createHeaderFooterPolicy();
      policy
          .createHeader(XWPFHeaderFooterPolicy.FIRST)
          .createParagraph()
          .createRun()
          .setText("Stadt Musterstadt");
      addParagraph(doc, "Fachlicher Inhalt.");
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "nur-erste-seite.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().getFirst().getText()).isEqualTo("Stadt Musterstadt");
  }

  @Test
  void aPageFieldsCachedValueIsExcludedButSurroundingTextIsKept() throws IOException {
    // regression guard for #1145 review, B3: a Word field's cached last-computed value (the
    // separate/end run sequence) is wrong for every page but the one it was current on and must
    // not become indexed content; the static text around it is real content and must survive.
    Path file = tempDir.resolve("seitenzahl-fusszeile.docx");
    try (XWPFDocument doc = new XWPFDocument()) {
      XWPFHeaderFooterPolicy policy = doc.createHeaderFooterPolicy();
      XWPFFooter footer = policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
      XWPFParagraph paragraph = footer.createParagraph();
      paragraph.createRun().setText("Seite ");
      paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
      paragraph.createRun().getCTR().addNewInstrText().setStringValue(" PAGE ");
      paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);
      paragraph.createRun().setText("1");
      paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
      addParagraph(doc, "Fachlicher Inhalt.");
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "seitenzahl-fusszeile.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().getFirst().getText()).isEqualTo("Seite");
  }

  @Test
  void aFooterThatIsOnlyAPageFieldContributesNoLeadingChunkAtAll() throws IOException {
    // regression guard for #1145 review, B3 (the safety net): once the field value is excluded,
    // nothing but digits/whitespace is left - RepeatingHeaderChunk's letter check must reject it
    // rather than index a chunk of pure noise.
    Path file = tempDir.resolve("nur-seitenzahl.docx");
    try (XWPFDocument doc = new XWPFDocument()) {
      XWPFHeaderFooterPolicy policy = doc.createHeaderFooterPolicy();
      XWPFFooter footer = policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
      XWPFParagraph paragraph = footer.createParagraph();
      paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
      paragraph.createRun().getCTR().addNewInstrText().setStringValue(" PAGE ");
      paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);
      paragraph.createRun().setText("1");
      paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
      addParagraph(doc, "Fachlicher Inhalt.");
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "nur-seitenzahl.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).startsWith("Fachlicher Inhalt");
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
