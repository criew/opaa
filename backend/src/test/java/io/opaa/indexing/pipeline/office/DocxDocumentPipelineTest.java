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
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSimpleField;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;

/**
 * The DOCX pipeline (ingestion-pipelines.md Teil 2): the cut follows heading levels taken from a
 * paragraph's own formatting (built-in style or direct outline level), mirroring
 * MarkdownDocumentPipelineTest/HtmlDocumentPipelineTest for the same section-building rules.
 */
class DocxDocumentPipelineTest {

  @TempDir Path tempDir;

  private final DocxDocumentPipeline pipeline = new DocxDocumentPipeline();

  @Test
  void claimsExactlyDocx() {
    assertThat(pipeline.handledFormats()).containsExactly(".docx");
    assertThat(pipeline.id()).isEqualTo("docx");
    assertThat(pipeline.version()).isEqualTo((short) 3);
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
    // Gebührenverzeichnisse und Formulare sind praktisch immer Tabellen
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
    // the styleId is not reliably English regardless of authoring locale -
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
  void headerTextAloneDoesNotDefeatTheScanEmptyDeckGuard() throws IOException {
    // regression guard for #1145: a scanned authority letter carries its
    // letterhead in the Kopf-/Fusszeile just like a text-layer document, so header/footer text is
    // no evidence this document itself has extractable content. Adding the header/footer chunk
    // before the guard check would report CHUNKED instead of NO_CONTENT - silently reopening the
    // stille-Leer-Index-Fehlfunktion this guard exists to prevent.
    Path file = tempDir.resolve("nur-kopfzeile.docx");
    try (XWPFDocument doc = new XWPFDocument()) {
      XWPFHeaderFooterPolicy policy = doc.createHeaderFooterPolicy();
      XWPFHeader header = policy.createHeader(XWPFHeaderFooterPolicy.DEFAULT);
      header.createParagraph().createRun().setText("Stadt Musterstadt");
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "nur-kopfzeile.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void identicalHeaderVariantsContributeOnlyOnce() throws IOException {
    // regression guard for #1145: reading every header/footer part (not just the
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
    // regression guard for #1145: "Erste Seite anders" (w:titlePg) is the common
    // German-authority-letterhead layout - the letterhead lives exclusively in the FIRST header
    // part, never in DEFAULT. A handler reading only
    // XWPFHeaderFooterPolicy#getDefaultHeader() would miss exactly that case.
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
    // regression guard for #1145: a Word field's cached last-computed value (the
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
    // regression guard for #1145: once the field value is excluded,
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
  void aRunWithSeveralWTChildrenIsRenderedInFullNotJustItsFirstWT() throws IOException {
    // regression guard for #1145: Word routinely writes a tab-separated
    // multi-column letterhead ("Stadt Musterstadt<TAB>Az. 12-34/2026") as one run with several
    // w:t/w:tab children, not one run per column - built here directly at the CT level (not via
    // XWPFRun#setText, which always produces the single-w:t form and would hide this bug).
    // XWPFRun#getText(int) returns only a run's first w:t child; #text() renders every child.
    Path file = tempDir.resolve("mehrspaltige-kopfzeile.docx");
    try (XWPFDocument doc = new XWPFDocument()) {
      XWPFHeaderFooterPolicy policy = doc.createHeaderFooterPolicy();
      XWPFHeader header = policy.createHeader(XWPFHeaderFooterPolicy.DEFAULT);
      XWPFParagraph paragraph = header.createParagraph();
      CTR ctr = paragraph.createRun().getCTR();
      ctr.addNewT().setStringValue("Stadt Musterstadt");
      ctr.addNewTab();
      ctr.addNewT().setStringValue("Az. 12-34/2026");
      addParagraph(doc, "Fachlicher Inhalt.");
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "mehrspaltige-kopfzeile.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().getFirst().getText()).isEqualTo("Stadt Musterstadt\tAz. 12-34/2026");
  }

  @Test
  void trackedChangesDeletionTextIsExcludedEvenThoughXWPFRunTextDoesNotExcludeIt()
      throws IOException {
    // regression guard for #1145: XWPFRun#text() excludes w:instrText but
    // not w:delText - the delText exclusion is carried entirely by this pipeline's own
    // ctr.sizeOfDelTextArray() check, not by POI. Without it, a header/footer run holding
    // tracked-changes deletion text (deleted but pending review) would be indexed as if it were
    // current content.
    Path file = tempDir.resolve("geloeschter-kopfzeilentext.docx");
    try (XWPFDocument doc = new XWPFDocument()) {
      XWPFHeaderFooterPolicy policy = doc.createHeaderFooterPolicy();
      XWPFHeader header = policy.createHeader(XWPFHeaderFooterPolicy.DEFAULT);
      XWPFParagraph paragraph = header.createParagraph();
      CTR prefix = paragraph.createRun().getCTR();
      prefix.addNewT().setStringValue("Bleibt ");
      CTR deleted = paragraph.createRun().getCTR();
      deleted.addNewDelText().setStringValue("Geloescht");
      CTR suffix = paragraph.createRun().getCTR();
      suffix.addNewT().setStringValue("Ende");
      addParagraph(doc, "Fachlicher Inhalt.");
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(
            DocumentPipelineSource.ofFile(file, "geloeschter-kopfzeilentext.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().getFirst().getText())
        .isEqualTo("Bleibt Ende")
        .doesNotContain("Geloescht");
  }

  @Test
  void aFldSimpleFieldsCachedValueIsExcluded() throws IOException {
    // regression guard for #1145: w:fldSimple (LibreOffice's .docx
    // export form for a page number, as opposed to Word's own begin/separate/end run sequence) is
    // exposed by POI as an XWPFFieldRun that carries neither w:fldChar nor w:instrText, so the
    // begin/separate/end state machine alone never sees it - built here via CTP#addNewFldSimple()
    // directly, the form the earlier state-machine-only fix could not have caught.
    Path file = tempDir.resolve("fldsimple-fusszeile.docx");
    try (XWPFDocument doc = new XWPFDocument()) {
      XWPFHeaderFooterPolicy policy = doc.createHeaderFooterPolicy();
      XWPFFooter footer = policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
      XWPFParagraph paragraph = footer.createParagraph();
      CTP ctp = paragraph.getCTP();
      CTR prefix = ctp.addNewR();
      prefix.addNewT().setStringValue("Seite ");
      CTSimpleField field = ctp.addNewFldSimple();
      field.setInstr(" PAGE ");
      field.addNewR().addNewT().setStringValue("7");
      CTR suffix = ctp.addNewR();
      suffix.addNewT().setStringValue(" von 9");
      addParagraph(doc, "Fachlicher Inhalt.");
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "fldsimple-fusszeile.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().getFirst().getText()).isEqualTo("Seite  von 9");
  }

  @Test
  void aNestedFieldWithNoResultPartOfItsOwnDoesNotEndTheOuterFieldsExclusionEarly()
      throws IOException {
    // regression guard for #1162: a nested field with no result part of its own (BEGIN/instrText/
    // END, never updated by Word - here the inner PAGE field) must not end the surrounding field's
    // (here IF) exclusion early just because its own END fires before the outer field's END. Built
    // at the CT level, not via XWPFRun#setText, to get the exact begin/separate/end run sequence.
    Path file = tempDir.resolve("verschachteltes-feld.docx");
    try (XWPFDocument doc = new XWPFDocument()) {
      XWPFHeaderFooterPolicy policy = doc.createHeaderFooterPolicy();
      XWPFFooter footer = policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
      XWPFParagraph paragraph = footer.createParagraph();
      paragraph.createRun().setText("Vor");
      paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
      paragraph.createRun().getCTR().addNewInstrText().setStringValue(" IF ");
      paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);
      paragraph.createRun().setText("LEAK-OUTER1 ");
      paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
      paragraph.createRun().getCTR().addNewInstrText().setStringValue(" PAGE ");
      paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
      paragraph.createRun().setText("LEAK-OUTER2 Ende");
      paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
      paragraph.createRun().setText(" Nach");
      addParagraph(doc, "Fachlicher Inhalt.");
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "verschachteltes-feld.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().getFirst().getText())
        .isEqualTo("Vor Nach")
        .doesNotContain("LEAK-OUTER1", "LEAK-OUTER2");
  }

  @Test
  void aFieldNestedInsideAnOuterFieldsInstructionPartHasBothCachedValuesExcluded()
      throws IOException {
    // regression guard for #1162: a field nested inside its outer field's
    // own instruction part (BEGIN outer/instr/BEGIN inner/instr/SEPARATE innerValue END/SEPARATE
    // outerValue END), as opposed to being nested inside the outer field's result - both the
    // inner and the outer cached value must be excluded. Locks in the stack's frame-per-BEGIN
    // behavior against a future refactor back to a plain separated-frame counter.
    Path file = tempDir.resolve("feld-im-instruktionsteil.docx");
    try (XWPFDocument doc = new XWPFDocument()) {
      XWPFHeaderFooterPolicy policy = doc.createHeaderFooterPolicy();
      XWPFFooter footer = policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
      XWPFParagraph paragraph = footer.createParagraph();
      paragraph.createRun().setText("Vor");
      paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
      paragraph.createRun().getCTR().addNewInstrText().setStringValue(" IF ");
      paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
      paragraph.createRun().getCTR().addNewInstrText().setStringValue(" PAGE ");
      paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);
      paragraph.createRun().setText("INNERWERT");
      paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
      paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);
      paragraph.createRun().setText("OUTERWERT");
      paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
      paragraph.createRun().setText(" Nach");
      addParagraph(doc, "Fachlicher Inhalt.");
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "feld-im-instruktionsteil.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().getFirst().getText())
        .isEqualTo("Vor Nach")
        .doesNotContain("INNERWERT", "OUTERWERT");
  }

  @Test
  void anEndWithNoOpenFieldDoesNotSwallowSubsequentText() throws IOException {
    // Robustness property: an END with no matching BEGIN must not drive the
    // frame stack into a state where it later excludes real text.
    Path file = tempDir.resolve("end-ohne-begin.docx");
    try (XWPFDocument doc = new XWPFDocument()) {
      XWPFHeaderFooterPolicy policy = doc.createHeaderFooterPolicy();
      XWPFFooter footer = policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
      XWPFParagraph paragraph = footer.createParagraph();
      paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
      paragraph.createRun().setText("Bleibt");
      addParagraph(doc, "Fachlicher Inhalt.");
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "end-ohne-begin.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().getFirst().getText()).isEqualTo("Bleibt");
  }

  @Test
  void anUnclosedFieldOnlySwallowsTheRestOfItsOwnParagraph() throws IOException {
    // Robustness property: an unbalanced BEGIN/SEPARATE with no matching END
    // resets at the next paragraph rather than leaking into it - the frame stack is local to each
    // paragraph's own call of paragraphTextExcludingFieldValues.
    Path file = tempDir.resolve("unbalanciertes-feld.docx");
    try (XWPFDocument doc = new XWPFDocument()) {
      XWPFHeaderFooterPolicy policy = doc.createHeaderFooterPolicy();
      XWPFFooter footer = policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
      XWPFParagraph unclosed = footer.createParagraph();
      unclosed.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
      unclosed.createRun().getCTR().addNewInstrText().setStringValue(" PAGE ");
      unclosed.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);
      unclosed.createRun().setText("LEAK");
      XWPFParagraph next = footer.createParagraph();
      next.createRun().setText("Naechster Absatz");
      addParagraph(doc, "Fachlicher Inhalt.");
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "unbalanciertes-feld.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().getFirst().getText())
        .contains("Naechster Absatz")
        .doesNotContain("LEAK");
  }

  @Test
  void aNonBreakingSpaceVariantIsDeduplicatedAgainstThePlainSpaceVariant() throws IOException {
    // regression guard for #1145: a non-breaking space (U+00A0) is routine in
    // an authority letterhead's column separators; \s alone does not match it, so a variant using
    // NBSP and the default using a plain space would otherwise both survive deduplication.
    Path file = tempDir.resolve("geschuetztes-leerzeichen.docx");
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
          .setText("Stadt\u00A0Musterstadt");
      addParagraph(doc, "Fachlicher Inhalt.");
      write(doc, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "geschuetztes-leerzeichen.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    long occurrences = result.chunks().getFirst().getText().split("Stadt", -1).length - 1;
    assertThat(occurrences).isEqualTo(1);
  }

  @Test
  void aFilelessSourceIsAParseFailure() {
    // A DOCX pipeline is only ever reached through a genuine .docx file (never RSS-extracted
    // text, ADR-0017 decision 2) - defensive fallback, mirrors PdfDocumentPipeline/
    // PptxDocumentPipeline.
    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofExtractedText("irrelevanter Text", "quelle.docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.PARSE_FAILED);
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
  void aFileThatIsNotAValidDocxIsAParseFailure() throws IOException {
    Path file = tempDir.resolve("kaputt.docx");
    Files.writeString(file, "das ist kein docx");

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "kaputt.docx", ".docx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.PARSE_FAILED);
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
