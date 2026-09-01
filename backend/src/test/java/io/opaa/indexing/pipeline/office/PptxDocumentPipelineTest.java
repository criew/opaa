package io.opaa.indexing.pipeline.office;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import java.awt.Rectangle;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.sl.usermodel.TextShape;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The PPTX pipeline (#1061; ingestion-pipelines.md Teil 2: "PPTX | Eine Folie = ein Chunk"): every
 * slide with text becomes exactly one chunk, carrying its title and slide number as location; a
 * blank slide alongside others still yields a (near-empty) chunk, but a presentation where no slide
 * carries any text at all is rejected as NO_EXTRACTABLE_TEXT rather than silently indexed.
 */
class PptxDocumentPipelineTest {

  @TempDir Path tempDir;

  private final PptxDocumentPipeline pipeline = new PptxDocumentPipeline();

  @Test
  void claimsExactlyPptx() {
    assertThat(pipeline.handledFormats()).containsExactly(".pptx");
    assertThat(pipeline.id()).isEqualTo("pptx");
    assertThat(pipeline.version()).isEqualTo((short) 1);
  }

  @Test
  void oneChunkPerSlideCarryingTitleAndSlideNumber() throws IOException {
    Path file = tempDir.resolve("praesentation.pptx");
    try (XMLSlideShow show = new XMLSlideShow()) {
      addSlide(show, "Einfuehrung", "Willkommen zur Buergerversammlung.", null);
      addSlide(
          show, "Gebuehren", "Der Personalausweis kostet 37,00 EUR.", "Bitte langsam sprechen.");
      write(show, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "praesentation.pptx", ".pptx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().get(0).getText())
        .startsWith("Einfuehrung")
        .contains("Buergerversammlung");
    assertThat(result.chunks().get(0).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Folie 1: Einfuehrung");
    assertThat(result.chunks().get(1).getText())
        .startsWith("Gebuehren")
        .contains("37,00 EUR")
        .contains("Notizen: Bitte langsam sprechen.");
    assertThat(result.chunks().get(1).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Folie 2: Gebuehren");
    // The keys actually produced must stay within what the pipeline declares - the guard
    // DocumentPipelineRegistryRoutingIntegrationTest#everyPipelineDeclaresExactlyItsOwnMetadataKeys
    // checks the declaration; this checks the declaration against real output.
    Set<String> actualKeys =
        result.chunks().stream().flatMap(c -> c.getMetadata().keySet().stream()).collect(toSet());
    assertThat(pipeline.passthroughMetadataKeys()).containsAll(actualKeys);
  }

  @Test
  void aBlankSlideAlongsideOthersStillBecomesItsOwnChunk() throws IOException {
    Path file = tempDir.resolve("mit-leerer-folie.pptx");
    try (XMLSlideShow show = new XMLSlideShow()) {
      addSlide(show, "Einfuehrung", "Willkommen.", null);
      show.createSlide();
      write(show, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "mit-leerer-folie.pptx", ".pptx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().get(1).getText()).isEqualTo("Folie 2");
  }

  @Test
  void aPresentationWhereNoSlideHasAnyTextIsRejectedAsNoExtractableText() throws IOException {
    // #1104 review, wichtig 4: without this guard the #1055 "silent empty index" failure mode
    // returns for PPTX - N content-free "Folie n" chunks would look like a successful index.
    Path file = tempDir.resolve("nur-bilder.pptx");
    try (XMLSlideShow show = new XMLSlideShow()) {
      show.createSlide();
      show.createSlide();
      write(show, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "nur-bilder.pptx", ".pptx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void aTableIsReadRowByRowAndAddedAsBodyText() throws IOException {
    Path file = tempDir.resolve("tabelle.pptx");
    try (XMLSlideShow show = new XMLSlideShow()) {
      XSLFSlide slide = show.createSlide();
      XSLFTable table = slide.createTable(2, 2);
      table.getCell(0, 0).setText("Leistung");
      table.getCell(0, 1).setText("Gebuehr");
      table.getCell(1, 0).setText("Personalausweis");
      table.getCell(1, 1).setText("37,00 EUR");
      write(show, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "tabelle.pptx", ".pptx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText())
        .contains("Leistung | Gebuehr")
        .contains("Personalausweis | 37,00 EUR");
  }

  @Test
  void aGroupShapeIsDescendedIntoRecursively() throws IOException {
    Path file = tempDir.resolve("gruppe.pptx");
    try (XMLSlideShow show = new XMLSlideShow()) {
      XSLFSlide slide = show.createSlide();
      XSLFGroupShape group = slide.createGroup();
      group.setAnchor(new Rectangle(0, 0, 400, 200));
      group.setInteriorAnchor(new Rectangle(0, 0, 400, 200));
      XSLFTextBox boxInGroup = group.createTextBox();
      boxInGroup.setAnchor(new Rectangle(0, 0, 400, 100));
      boxInGroup.setText("Text in einer Gruppe.");
      write(show, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "gruppe.pptx", ".pptx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).contains("Text in einer Gruppe.");
  }

  @Test
  void theTitleShapeIsExcludedByIdentityNotByTextEquality() throws IOException {
    // #1104 review, Nit 7: a body shape that happens to repeat the title's exact wording must not
    // also be silently dropped by a text-equality check.
    Path file = tempDir.resolve("wiederholter-titel.pptx");
    try (XMLSlideShow show = new XMLSlideShow()) {
      var layout = show.getSlideMasters().get(0).getLayout(SlideLayout.TITLE_ONLY);
      XSLFSlide slide = show.createSlide(layout);
      var titleShape = slide.getPlaceholder(Placeholder.TITLE);
      ((XSLFTextShape) titleShape).setText("Wiederholt");
      XSLFTextBox bodyBox = slide.createTextBox();
      bodyBox.setAnchor(new Rectangle(0, 60, 400, 200));
      bodyBox.setText("Wiederholt");
      write(show, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "wiederholter-titel.pptx", ".pptx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    // Title line once, body once - both survive, the body copy is not treated as the title shape.
    assertThat(result.chunks().getFirst().getText()).isEqualTo("Wiederholt\n\nWiederholt");
  }

  @Test
  void aNotesTextBoxWithoutAPlaceholderTypeIsIncludedWithoutThrowing() throws IOException {
    // #1104 review round 2, wichtig 2: XSLFTextShape#getTextType() returns null for an ordinary
    // text box that is not inherited from a notes-master placeholder - the existing notes tests
    // only ever hit placeholders, so this NPE (Set.of(...)#contains(null)) went unnoticed and
    // crashed the whole presentation, not just this one slide.
    Path file = tempDir.resolve("notiz-textfeld.pptx");
    try (XMLSlideShow show = new XMLSlideShow()) {
      XSLFSlide slide = show.createSlide();
      slide.createTextBox().setText("Folieninhalt.");
      var notesSlide = show.getNotesSlide(slide);
      XSLFTextBox notesBox = notesSlide.createTextBox();
      notesBox.setAnchor(new Rectangle(0, 0, 400, 100));
      notesBox.setText("Freitext ohne Platzhaltertyp.");
      write(show, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "notiz-textfeld.pptx", ".pptx"));

    // The point is that this does not throw (the notes slide's own BODY placeholder, with its
    // master's literal prompt text, sits alongside the plain text box added here and is not
    // filtered - only slide-number/date/footer/header placeholders are, see this class's own
    // NON_CONTENT_NOTES_PLACEHOLDERS).
    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText())
        .contains("Folieninhalt.")
        .contains("Notizen:")
        .contains("Freitext ohne Platzhaltertyp.");
  }

  @Test
  void aPresentationWithoutAnySlideHasNoContent() throws IOException {
    Path file = tempDir.resolve("keine-folien.pptx");
    try (XMLSlideShow show = new XMLSlideShow()) {
      write(show, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "keine-folien.pptx", ".pptx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
  }

  @Test
  void aFileThatIsNotAValidPptxHasNoContent() throws IOException {
    Path file = tempDir.resolve("kaputt.pptx");
    Files.writeString(file, "das ist kein pptx");

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "kaputt.pptx", ".pptx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
  }

  @Test
  void aFilelessSourceHasNoContent() {
    // A PPTX pipeline is only ever reached through a genuine .pptx file (never RSS-extracted
    // text, ADR-0017 decision 2) - defensive fallback, mirrors PdfDocumentPipeline/
    // DocxDocumentPipeline.
    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofExtractedText("irrelevanter Text", "quelle.pptx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
    assertThat(result.chunks()).isEmpty();
  }

  private static void addSlide(XMLSlideShow show, String title, String body, String notes) {
    // The "Title Only" master layout carries a title placeholder and nothing else; createSlide
    // (layout) copies that placeholder shape onto the new slide, which the default no-arg
    // createSlide() does not guarantee. XSLFTextShape#setText (not the placeholder-details
    // wrapper's own setText, which is a no-op on this shape) replaces the layout's literal prompt
    // text ("Click to edit Master title style").
    var layout = show.getSlideMasters().get(0).getLayout(SlideLayout.TITLE_ONLY);
    XSLFSlide slide = show.createSlide(layout);
    var titleShape = slide.getPlaceholder(Placeholder.TITLE);
    ((XSLFTextShape) titleShape).setText(title);
    XSLFTextBox bodyBox = slide.createTextBox();
    bodyBox.setAnchor(new Rectangle(0, 60, 400, 200));
    bodyBox.setText(body);
    if (notes != null) {
      var notesSlide = show.getNotesSlide(slide);
      for (var shape : notesSlide.getShapes()) {
        if (shape instanceof TextShape<?, ?> textShape) {
          textShape.setText(notes);
        }
      }
    }
  }

  private static void write(XMLSlideShow show, Path file) throws IOException {
    try (OutputStream out = Files.newOutputStream(file)) {
      show.write(out);
    }
  }
}
