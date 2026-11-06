package io.opaa.format.file.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.chunk.ChunkAnchor;
import io.opaa.format.chunk.ChunkAnchors;
import io.opaa.format.chunk.ChunkMetadataKeys;
import io.opaa.format.shared.HeadingSectionSplitterFixture;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Chunks of a recognised scan. {@code docling-ocr-test.json} is the RapidOCR ground truth of
 * docling's own test scan ({@code tests/data/ocr/groundtruth/rapidocr/full_page} in
 * docling-project/docling, MIT); {@code docling-gebuehrensatzung.json} is built after the measured
 * output of docling-serve: element boxes {@code BOTTOMLEFT}, table cells {@code TOPLEFT}, both in
 * PDF points of {@code pages[n].size}.
 */
class PdfOcrDocumentFormatTest {

  private static final double PAGE_WIDTH = 595.2;
  private static final double PAGE_HEIGHT = 841.92;
  private static final String TITLE = "Satzung über die Erhebung von Gebühren";
  private static final String PARAGRAPH_1 = TITLE + " › § 1 Gebührenpflicht";
  private static final String PARAGRAPH_2 = TITLE + " › § 2 Gebührenhöhe";

  private final PdfOcrDocumentFormat format =
      new PdfOcrDocumentFormat(HeadingSectionSplitterFixture.DEFAULT);

  @Test
  void theRealDoclingOutputBecomesOneChunkFramedWhereTheTextStandsOnThePage() throws IOException {
    DocumentFormatResult result = run(RecognizedPdf.docling("cfg-1", fixture("docling-ocr-test")));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    Document chunk = result.chunks().getFirst();
    assertThat(result.chunks()).hasSize(1);
    assertThat(chunk.getText())
        .isEqualTo(
            "Docling bundles PDF document conversion to JSON and Markdown in an easy self"
                + " contained package");
    assertThat(chunk.getMetadata())
        .containsEntry(ChunkMetadataKeys.LOCATION_METADATA_KEY, "S. 1")
        .containsEntry(
            ChunkMetadataKeys.TEXT_RECOGNITION_METADATA_KEY,
            Map.of("connectionType", "DOCLING_SERVE", "configurationId", "cfg-1"));
    ChunkAnchor anchor = ChunkAnchors.read(chunk.getMetadata());
    assertThat(anchor.pageFrom()).isEqualTo(1);
    assertThat(anchor.pageTo()).isEqualTo(1);
    assertThat(anchor.textOrigin()).isEqualTo(new ChunkAnchor.TextOrigin("DOCLING_SERVE"));
    // bbox l 68.5, t 769.77, r 507.81, b 687.87 from the bottom edge
    assertRegion(
        anchor.regions().getFirst(), 1, 68.5, PAGE_HEIGHT - 769.77, 507.81, PAGE_HEIGHT - 687.87);
  }

  @Test
  void headingsCutTheScanIntoSectionsWithoutPageHeaderFooterOrPicture() throws IOException {
    DocumentFormatResult result =
        run(RecognizedPdf.docling("cfg-1", fixture("docling-gebuehrensatzung")));

    List<String> texts = result.chunks().stream().map(Document::getText).toList();
    assertThat(texts)
        .containsExactly(
            PARAGRAPH_1
                + "\n\n1. Für die Benutzung wird eine Gebühr erhoben."
                + "\n\n2. Gebührenschuldner ist, wer die Leistung beantragt.",
            PARAGRAPH_2 + "\n\nLeistung | Gebühr\nBeglaubigung | 10 Euro\nAuskunft | 5 Euro",
            PARAGRAPH_2 + "\n\nDiese Satzung tritt am Tage nach ihrer Bekanntmachung in Kraft.");
    assertThat(String.join("\n", texts)).doesNotContain("Amtsblatt", "Seite 1");
    assertThat(result.chunks())
        .extracting(chunk -> chunk.getMetadata().get(ChunkMetadataKeys.LOCATION_METADATA_KEY))
        .containsExactly(
            "S. 1 · Abschn. " + PARAGRAPH_1,
            "S. 1 · Abschn. " + PARAGRAPH_2,
            "S. 2 · Abschn. " + PARAGRAPH_2);
    assertThat(ChunkAnchors.read(result.chunks().get(2).getMetadata()).path())
        .containsExactly(TITLE, "§ 2 Gebührenhöhe");
    assertThat(result.properties().firstHeading()).isEqualTo(TITLE);
  }

  @Test
  void elementBoxesCountFromTheBottomEdgeAndTableCellsFromTheTop() throws IOException {
    DocumentFormatResult result =
        run(RecognizedPdf.docling("cfg-1", fixture("docling-gebuehrensatzung")));

    // § 1 heading (t 740) to the second list item (b 680), all BOTTOMLEFT
    ChunkAnchor list = ChunkAnchors.read(result.chunks().get(0).getMetadata());
    assertThat(list.regions()).hasSize(1);
    assertRegion(list.regions().getFirst(), 1, 70, PAGE_HEIGHT - 740, 510, PAGE_HEIGHT - 680);
    // § 2 heading (BOTTOMLEFT t 660) down to the last data row (TOPLEFT b 272)
    ChunkAnchor table = ChunkAnchors.read(result.chunks().get(1).getMetadata());
    assertRegion(table.regions().getFirst(), 1, 70, PAGE_HEIGHT - 660, 520, 272);
    ChunkAnchor lastPage = ChunkAnchors.read(result.chunks().get(2).getMetadata());
    assertThat(lastPage.pageFrom()).isEqualTo(2);
    assertRegion(lastPage.regions().getFirst(), 2, 70, PAGE_HEIGHT - 800, 520, PAGE_HEIGHT - 780);
  }

  @Test
  void eachTableRowIsFramedByItsOwnCells() throws IOException {
    List<RecognizedPdfBlocks.Block> rows =
        RecognizedPdfBlocks.of(RecognizedPdf.docling("cfg-1", fixture("docling-gebuehrensatzung")))
            .stream()
            .filter(block -> block.kind() == RecognizedPdfBlocks.Kind.TABLE_ROW)
            .toList();

    assertThat(rows)
        .extracting(RecognizedPdfBlocks.Block::text)
        .containsExactly("Beglaubigung | 10 Euro", "Auskunft | 5 Euro");
    assertThat(rows.getFirst().tableHeader()).isEqualTo("Leistung | Gebühr");
    assertRegion(rows.getFirst().regions().getFirst(), 1, 70, 232, 520, 252);
    assertRegion(rows.get(1).regions().getFirst(), 1, 70, 252, 520, 272);
  }

  @Test
  void aRowOfATableContinuedOnTheNextPageIsFramedOnThatPage() throws IOException {
    tools.jackson.databind.node.ObjectNode document =
        (tools.jackson.databind.node.ObjectNode) fixture("docling-gebuehrensatzung");
    tools.jackson.databind.node.ObjectNode table =
        (tools.jackson.databind.node.ObjectNode) document.path("tables").get(0);
    // the table's part on page 2: from 41.92 to 101.92 points below the top edge
    tools.jackson.databind.node.ObjectNode part =
        ((tools.jackson.databind.node.ArrayNode) table.path("prov")).addObject();
    part.put("page_no", 2);
    part.putObject("bbox")
        .put("l", 70.0)
        .put("t", 800.0)
        .put("r", 520.0)
        .put("b", 740.0)
        .put("coord_origin", "BOTTOMLEFT");
    tools.jackson.databind.node.ArrayNode cells =
        (tools.jackson.databind.node.ArrayNode) table.path("data").path("table_cells");
    cell(cells.addObject(), 3, 0, "Abschrift", 70, 300);
    cell(cells.addObject(), 3, 1, "3 Euro", 300, 520);

    List<RecognizedPdfBlocks.Block> rows =
        RecognizedPdfBlocks.of(RecognizedPdf.docling("cfg-1", document)).stream()
            .filter(block -> block.kind() == RecognizedPdfBlocks.Kind.TABLE_ROW)
            .toList();

    assertThat(rows)
        .extracting(RecognizedPdfBlocks.Block::text)
        .containsExactly("Beglaubigung | 10 Euro", "Auskunft | 5 Euro", "Abschrift | 3 Euro");
    assertThat(rows.get(1).pageFrom()).isEqualTo(1);
    RecognizedPdfBlocks.Block continued = rows.get(2);
    assertThat(continued.pageFrom()).isEqualTo(2);
    assertThat(continued.pageTo()).isEqualTo(2);
    assertRegion(continued.regions().getFirst(), 2, 70, 60, 520, 80);
  }

  private static void cell(
      tools.jackson.databind.node.ObjectNode cell,
      int row,
      int column,
      String text,
      double left,
      double right) {
    cell.putObject("bbox")
        .put("l", left)
        .put("t", 60.0)
        .put("r", right)
        .put("b", 80.0)
        .put("coord_origin", "TOPLEFT");
    cell.put("start_row_offset_idx", row);
    cell.put("start_col_offset_idx", column);
    cell.put("text", text);
    cell.put("column_header", false);
  }

  @Test
  void aVisionModelsPagesAreCutByTheirMarkdownHeadingsWithoutFrames() {
    DocumentFormatResult result =
        run(
            RecognizedPdf.vision(
                "cfg-2",
                List.of(
                    "# Bescheid\n\nDer Antrag wird\nbewilligt.\n\n| Posten | Betrag |\n|---|---|\n"
                        + "| Gebühr | 10 Euro |",
                    "Gegen diesen Bescheid ist Widerspruch zulässig.")));

    assertThat(result.chunks())
        .extracting(Document::getText)
        .containsExactly(
            "Bescheid\n\nDer Antrag wird\nbewilligt.\n\n| Posten | Betrag |\n| Gebühr | 10 Euro |"
                + "\n\nGegen diesen Bescheid ist Widerspruch zulässig.");
    ChunkAnchor anchor = ChunkAnchors.read(result.chunks().getFirst().getMetadata());
    assertThat(anchor.pageFrom()).isEqualTo(1);
    assertThat(anchor.pageTo()).isEqualTo(2);
    assertThat(anchor.regions()).isNullOrEmpty();
    assertThat(anchor.textOrigin()).isEqualTo(new ChunkAnchor.TextOrigin("OPENAI_VISION"));
    assertThat(result.chunks().getFirst().getMetadata())
        .containsEntry(ChunkMetadataKeys.LOCATION_METADATA_KEY, "S. 1–2 · Abschn. Bescheid");
  }

  @Test
  void aRecognitionWithoutTextHasNoExtractableText() {
    DocumentFormatResult result = run(RecognizedPdf.vision("cfg-2", List.of("", "  ")));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.NO_EXTRACTABLE_TEXT);
  }

  @Test
  void theStoredRecognitionKeepsTextAndFramesButNoPageImage() throws IOException {
    RecognizedPdf recognized = RecognizedPdf.docling("cfg-1", fixture("docling-gebuehrensatzung"));

    String stored = recognized.toJson();

    assertThat(stored).doesNotContain("base64", "\"image\"").contains("Beglaubigung");
    RecognizedPdf parsed = RecognizedPdf.parse(stored);
    assertThat(parsed.connectionType()).isEqualTo(RecognizedPdf.DOCLING_SERVE);
    assertThat(parsed.configurationId()).isEqualTo("cfg-1");
    assertThat(parsed.pageCount()).isEqualTo(2);
    assertThat(run(parsed).chunks()).hasSize(3);
  }

  @Test
  void theFormatReadsOnlyAStoredRecognition() {
    assertThatThrownBy(() -> RecognizedPdf.parse("{\"version\":1}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                format.run(
                    DocumentFormatSource.ofFile(
                        java.nio.file.Path.of("scan.pdf"), "scan.pdf", ".pdf")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private DocumentFormatResult run(RecognizedPdf recognized) {
    return format.run(DocumentFormatSource.ofExtractedText(recognized.toJson(), "scan.pdf"));
  }

  private static JsonNode fixture(String name) throws IOException {
    try (InputStream in =
        PdfOcrDocumentFormatTest.class.getResourceAsStream("/text-recognition/" + name + ".json")) {
      return JsonMapper.builder()
          .build()
          .readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  /** The frame in page fractions from a box in points measured from the top edge. */
  private static void assertRegion(
      ChunkAnchor.Region region, int page, double left, double top, double right, double bottom) {
    assertThat(region.page()).isEqualTo(page);
    assertThat(region.x()).isCloseTo(left / PAGE_WIDTH, within(0.0002));
    assertThat(region.y()).isCloseTo(top / PAGE_HEIGHT, within(0.0002));
    assertThat(region.width()).isCloseTo((right - left) / PAGE_WIDTH, within(0.0002));
    assertThat(region.height()).isCloseTo((bottom - top) / PAGE_HEIGHT, within(0.0002));
  }
}
