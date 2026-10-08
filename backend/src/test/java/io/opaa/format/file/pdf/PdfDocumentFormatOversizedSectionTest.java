package io.opaa.format.file.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.chunk.ChunkMetadataKeys;
import io.opaa.format.shared.HeadingSectionSplitter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;

/**
 * A PDF section or page far larger than one chunk is indexed completely: split into several chunks
 * with the section's (or page's) Fundort on each, every one within the token budget of the
 * embedding step. The documents are synthetic Fristenlisten.
 */
class PdfDocumentFormatOversizedSectionTest {

  private static final String SECTION_TITLE = "II. Aufbewahrungsfristen";
  private static final String FINAL_SENTENCE =
      "Schlussbestimmung: Die Liste endet mit dieser Nummer.";

  @TempDir Path tempDir;

  private final PdfDocumentFormat pipeline = new PdfDocumentFormat();

  @Test
  void aSectionOfMoreThanThirtyThousandCharactersIsIndexedCompletely() throws IOException {
    // regression guard for #2327/#2328: the section was cut at 20,000 characters, and the one
    // remaining chunk exceeded the embedding step's token budget.
    Path file = tempDir.resolve("verwaltungsvorschrift-aktenaufbewahrung.pdf");
    List<String> lines = fristenLines(560);
    lines.add(FINAL_SENTENCE);
    try (PDDocument doc = new PDDocument()) {
      List<String> firstPage = new ArrayList<>(List.of(SECTION_TITLE));
      List<PDPage> pages = addPages(doc, firstPage, lines, PDRectangle.A4, 10, 14);
      PDDocumentOutline outline = new PDDocumentOutline();
      doc.getDocumentCatalog().setDocumentOutline(outline);
      outline.addLast(outlineItem(SECTION_TITLE, pages.getFirst()));
      doc.save(file.toFile());
    }

    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofFile(file, file.getFileName().toString(), ".pdf"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    List<Document> chunks = result.chunks();
    assertThat(String.join("", lines).length()).isGreaterThan(30_000);
    assertThat(joined(chunks)).contains(FINAL_SENTENCE).doesNotContain("[…gekürzt]");
    assertThat(chunks)
        .hasSizeGreaterThan(1)
        .allSatisfy(
            chunk -> {
              assertThat(chunk.getText()).startsWith(SECTION_TITLE);
              assertThat(chunk.getMetadata().get(ChunkMetadataKeys.LOCATION_METADATA_KEY))
                  .isEqualTo("Abschn. " + SECTION_TITLE);
            });
    assertThatCode(() -> new TokenCountBatchingStrategy().batch(chunks)).doesNotThrowAnyException();
  }

  @Test
  void aSinglePageBeyondTheHardLimitIsSplitAndEveryPartKeepsItsPageNumber() throws IOException {
    // Without an outline a page is a chunk; a page denser than the hard limit lost its tail.
    Path file = tempDir.resolve("dichte-seite.pdf");
    List<String> lines = fristenLines(330);
    lines.add(FINAL_SENTENCE);
    try (PDDocument doc = new PDDocument()) {
      addPage(doc, "Deckblatt der Fristenliste.");
      addPages(doc, new ArrayList<>(), lines, new PDRectangle(600, 4_200), 8, 10);
      doc.save(file.toFile());
    }

    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofFile(file, file.getFileName().toString(), ".pdf"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    List<Document> secondPage =
        result.chunks().stream()
            .filter(
                c -> "S. 2".equals(c.getMetadata().get(ChunkMetadataKeys.LOCATION_METADATA_KEY)))
            .toList();
    assertThat(String.join("", lines).length()).isGreaterThan(20_000);
    assertThat(secondPage).hasSizeGreaterThan(1);
    assertThat(joined(secondPage)).contains(FINAL_SENTENCE).doesNotContain("[…gekürzt]");
    assertThat(
            result.chunks().getFirst().getMetadata().get(ChunkMetadataKeys.LOCATION_METADATA_KEY))
        .isEqualTo("S. 1");
  }

  @Test
  void aPageBeyondTheTargetSizeIsSplitToTheTargetSize() throws IOException {
    // A page between the target size and the hard limit would exceed small embedding context
    // windows; it is split to the same target size as a section.
    Path file = tempDir.resolve("volle-seite.pdf");
    try (PDDocument doc = new PDDocument()) {
      addPages(doc, new ArrayList<>(), fristenLines(90), new PDRectangle(600, 1_200), 8, 10);
      doc.save(file.toFile());
    }

    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofFile(file, file.getFileName().toString(), ".pdf"));

    assertThat(String.join("", fristenLines(90)).length()).isBetween(4_500, 8_000);
    assertThat(result.chunks())
        .hasSizeGreaterThan(1)
        .allSatisfy(
            chunk -> {
              assertThat(chunk.getText().length())
                  .isLessThanOrEqualTo(HeadingSectionSplitter.SOFT_CHUNK_CHAR_LIMIT);
              assertThat(chunk.getMetadata().get(ChunkMetadataKeys.LOCATION_METADATA_KEY))
                  .isEqualTo("S. 1");
            });
  }

  /** Numbered, token-dense entries of a Fristenliste, one line each and short enough for a page. */
  private static List<String> fristenLines(int count) {
    List<String> lines = new ArrayList<>();
    for (int n = 1; n <= count; n++) {
      lines.add(
          String.format(
              "3.2.%d Az. %04d/%d-%02d vom %02d.%02d.%d, Frist %d Jahre (Kst. %05d).",
              n,
              (n * 37) % 10_000,
              2000 + n % 26,
              n % 100,
              1 + n % 28,
              1 + n % 12,
              1990 + n % 35,
              1 + n % 30,
              (n * 1_013) % 100_000));
    }
    return lines;
  }

  private static String joined(List<Document> chunks) {
    return String.join("\n", chunks.stream().map(Document::getText).toList());
  }

  private static PDPage addPage(PDDocument doc, String text) throws IOException {
    return addPages(doc, new ArrayList<>(List.of(text)), List.of(), PDRectangle.A4, 12, 15)
        .getFirst();
  }

  /**
   * Writes {@code leading} and then {@code lines} onto as many pages of {@code size} as they need.
   */
  private static List<PDPage> addPages(
      PDDocument doc,
      List<String> leading,
      List<String> lines,
      PDRectangle size,
      float fontSize,
      float leadingSpace)
      throws IOException {
    List<String> all = new ArrayList<>(leading);
    all.addAll(lines);
    int perPage = (int) ((size.getHeight() - 100) / leadingSpace);
    List<PDPage> pages = new ArrayList<>();
    for (int from = 0; from < all.size(); from += perPage) {
      PDPage page = new PDPage(size);
      doc.addPage(page);
      pages.add(page);
      try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), fontSize);
        stream.newLineAtOffset(40, size.getHeight() - 50);
        for (String line : all.subList(from, Math.min(from + perPage, all.size()))) {
          stream.showText(line);
          stream.newLineAtOffset(0, -leadingSpace);
        }
        stream.endText();
      }
    }
    return pages;
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
