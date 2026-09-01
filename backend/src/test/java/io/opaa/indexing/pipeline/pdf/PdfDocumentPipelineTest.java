package io.opaa.indexing.pipeline.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The PDF pipeline (#1061; ingestion-pipelines.md, Teil 1's parsing table and Teil 2): when the
 * catalog carries an outline, the cut follows it (every level, not just three); a document without
 * a resolvable outline falls back to one chunk per page.
 */
class PdfDocumentPipelineTest {

  @TempDir Path tempDir;

  private final PdfDocumentPipeline pipeline = new PdfDocumentPipeline();

  @Test
  void claimsExactlyPdf() {
    assertThat(pipeline.handledFormats()).containsExactly(".pdf");
    assertThat(pipeline.id()).isEqualTo("pdf");
    assertThat(pipeline.version()).isEqualTo((short) 1);
  }

  @Test
  void cutsFollowTheOutlineWithHeadingPathAndUnrestrictedNestingDepth() throws IOException {
    Path file = tempDir.resolve("satzung.pdf");
    try (PDDocument doc = new PDDocument()) {
      PDPage page1 = addPage(doc, "Diese Satzung regelt die Gebuehren der Stadt.");
      PDPage page2 = addPage(doc, "Fuer Personalausweise werden 37,00 EUR erhoben.");
      PDPage page3 = addPage(doc, "Es gilt eine Ermaessigung fuer Minderjaehrige.");

      PDDocumentOutline outline = new PDDocumentOutline();
      doc.getDocumentCatalog().setDocumentOutline(outline);
      PDOutlineItem chapter = outlineItem("§ 1 Personaldokumente", page2);
      PDOutlineItem paragraph = outlineItem("Abs. 2 Ermaessigung", page3);
      chapter.addLast(paragraph);
      outline.addLast(chapter);

      doc.save(file.toFile());
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "satzung.pdf", ".pdf"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    // Preamble (page 1, before the first outline entry), § 1 (page 2), Abs. 2 (page 3, nested).
    assertThat(result.chunks()).hasSize(3);
    assertThat(result.chunks().get(0).getText()).contains("regelt die Gebuehren");
    assertThat(result.chunks().get(0).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isNull();
    assertThat(result.chunks().get(1).getText())
        .startsWith("§ 1 Personaldokumente")
        .contains("37,00 EUR");
    assertThat(result.chunks().get(1).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Abschn. § 1 Personaldokumente");
    assertThat(result.chunks().get(2).getText())
        .startsWith("§ 1 Personaldokumente › Abs. 2 Ermaessigung")
        .contains("Minderjaehrige");
  }

  @Test
  void multipleOutlineEntriesOnTheSamePageEachGetTheirOwnBodyText() throws IOException {
    // The Satzung normal case: several §§ cataloged on the same page - each one's body text must
    // stay attached to its own heading, not bleed into (or entirely vanish behind) a sibling's.
    Path file = tempDir.resolve("mehrere-paragraphen.pdf");
    try (PDDocument doc = new PDDocument()) {
      PDPage page1 =
          addPageWithLines(
              doc,
              List.of(
                  "§ 1 Anwendungsbereich",
                  "Diese Satzung gilt fuer alle Antragstellenden.",
                  "§ 2 Gebuehren",
                  "Es werden 37,00 EUR erhoben."));

      PDDocumentOutline outline = new PDDocumentOutline();
      doc.getDocumentCatalog().setDocumentOutline(outline);
      outline.addLast(outlineItem("§ 1 Anwendungsbereich", page1));
      outline.addLast(outlineItem("§ 2 Gebuehren", page1));

      doc.save(file.toFile());
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "mehrere-paragraphen.pdf", ".pdf"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().get(0).getText())
        .startsWith("§ 1 Anwendungsbereich")
        .contains("Antragstellenden")
        .doesNotContain("37,00 EUR");
    assertThat(result.chunks().get(1).getText())
        .startsWith("§ 2 Gebuehren")
        .contains("37,00 EUR")
        .doesNotContain("Antragstellenden");
  }

  @Test
  void leadTextBeforeARunsFirstTitleStaysFindableInThePrecedingSection() throws IOException {
    // #1104 review round 2, wichtig 1: § 1's own body continues onto the page § 2/§ 3 are
    // bookmarked to (the run's shared page) before either title appears - that lead text must
    // stay attached to § 1, not vanish because it sits ahead of the run's first title.
    Path file = tempDir.resolve("fortlaufender-paragraph.pdf");
    try (PDDocument doc = new PDDocument()) {
      PDPage page1 =
          addPageWithLines(
              doc, List.of("§ 1 Anwendungsbereich", "Text zu Paragraph eins, Teil eins."));
      PDPage page2 =
          addPageWithLines(
              doc,
              List.of(
                  "Text zu Paragraph eins, Teil zwei (Fortsetzung).",
                  "§ 2 Gebuehren",
                  "Text zu Paragraph zwei.",
                  "§ 3 Schlussbestimmungen",
                  "Text zu Paragraph drei."));

      PDDocumentOutline outline = new PDDocumentOutline();
      doc.getDocumentCatalog().setDocumentOutline(outline);
      outline.addLast(outlineItem("§ 1 Anwendungsbereich", page1));
      outline.addLast(outlineItem("§ 2 Gebuehren", page2));
      outline.addLast(outlineItem("§ 3 Schlussbestimmungen", page2));

      doc.save(file.toFile());
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "fortlaufender-paragraph.pdf", ".pdf"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(3);
    assertThat(result.chunks().get(0).getText())
        .startsWith("§ 1 Anwendungsbereich")
        .contains("Teil eins")
        .contains("Fortsetzung");
    assertThat(result.chunks().get(1).getText())
        .startsWith("§ 2 Gebuehren")
        .contains("Paragraph zwei")
        .doesNotContain("Fortsetzung");
    assertThat(result.chunks().get(2).getText())
        .startsWith("§ 3 Schlussbestimmungen")
        .contains("Paragraph drei")
        .doesNotContain("Fortsetzung");
  }

  @Test
  void fallsBackToOnePagePerChunkWhenThereIsNoOutline() throws IOException {
    Path file = tempDir.resolve("ohne-gliederung.pdf");
    try (PDDocument doc = new PDDocument()) {
      addPage(doc, "Inhalt der ersten Seite.");
      addPage(doc, "Inhalt der zweiten Seite.");
      doc.save(file.toFile());
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "ohne-gliederung.pdf", ".pdf"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().get(0).getText()).contains("ersten Seite");
    assertThat(result.chunks().get(0).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("S. 1");
    assertThat(result.chunks().get(1).getText()).contains("zweiten Seite");
    assertThat(result.chunks().get(1).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("S. 2");
  }

  @Test
  void aTextlessPdfIsRejectedAsScanBeforeAnyPipelineSpecificExtraction() throws IOException {
    Path file = tempDir.resolve("scan.pdf");
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage(PDRectangle.A4));
      doc.save(file.toFile());
    }

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "scan.pdf", ".pdf"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void aFileThatIsNotAValidPdfHasNoContent() throws IOException {
    Path file = tempDir.resolve("kaputt.pdf");
    Files.writeString(file, "das ist kein pdf");

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "kaputt.pdf", ".pdf"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
  }

  @Test
  void aFilelessSourceHasNoContent() {
    // A PDF pipeline is only ever reached through a genuine .pdf file (never RSS-extracted text,
    // ADR-0017 decision 2) - defensive fallback, mirrors DocxDocumentPipeline/PptxDocumentPipeline.
    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofExtractedText("irrelevanter Text", "quelle.pdf"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
    assertThat(result.chunks()).isEmpty();
  }

  private static PDPage addPage(PDDocument doc, String text) throws IOException {
    return addPageWithLines(doc, List.of(text));
  }

  private static PDPage addPageWithLines(PDDocument doc, List<String> lines) throws IOException {
    PDPage page = new PDPage(PDRectangle.A4);
    doc.addPage(page);
    try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
      stream.beginText();
      stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
      stream.newLineAtOffset(50, 700);
      boolean first = true;
      for (String line : lines) {
        if (!first) {
          stream.newLineAtOffset(0, -15);
        }
        first = false;
        stream.showText(line);
      }
      stream.endText();
    }
    return page;
  }

  private static PDOutlineItem outlineItem(String title, PDPage page) {
    PDOutlineItem item = new PDOutlineItem();
    item.setTitle(title);
    PDPageXYZDestination destination = new PDPageXYZDestination();
    destination.setPage(page);
    item.setDestination(destination);
    return item;
  }
}
