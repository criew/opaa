package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.sl.usermodel.TextShape;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The PPTX pipeline (#1061; ingestion-pipelines.md Teil 2: "PPTX | Eine Folie = ein Chunk"): every
 * slide becomes exactly one chunk, carrying its title and slide number as location, and an
 * image-only slide still yields a (near-empty) chunk rather than being silently dropped.
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
  }

  @Test
  void aSlideWithNoTextStillBecomesItsOwnChunk() throws IOException {
    Path file = tempDir.resolve("leere-folie.pptx");
    try (XMLSlideShow show = new XMLSlideShow()) {
      show.createSlide();
      write(show, file);
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "leere-folie.pptx", ".pptx"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).isEqualTo("Folie 1");
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

  private static void addSlide(XMLSlideShow show, String title, String body, String notes) {
    // The "Title Only" master layout carries a title placeholder and nothing else; createSlide
    // (layout) copies that placeholder shape onto the new slide, which the default no-arg
    // createSlide() does not guarantee. XSLFTextShape#setText (not the placeholder-details
    // wrapper's own setText, which is a no-op on this shape) replaces the layout's literal prompt
    // text ("Click to edit Master title style").
    var layout =
        show.getSlideMasters()
            .get(0)
            .getLayout(org.apache.poi.xslf.usermodel.SlideLayout.TITLE_ONLY);
    XSLFSlide slide = show.createSlide(layout);
    var titleShape = slide.getPlaceholder(org.apache.poi.sl.usermodel.Placeholder.TITLE);
    ((org.apache.poi.xslf.usermodel.XSLFTextShape) titleShape).setText(title);
    XSLFTextBox bodyBox = slide.createTextBox();
    bodyBox.setAnchor(new java.awt.Rectangle(0, 60, 400, 200));
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
