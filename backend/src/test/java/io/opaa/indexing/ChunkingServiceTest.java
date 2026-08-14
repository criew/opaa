package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class ChunkingServiceTest {

  /**
   * Distinct, individually identifiable sentences. Repeating the same sentence would make the
   * overlap assertions below pass trivially, because any chunk would then "contain" any other
   * chunk's opening words.
   */
  private static String distinctSentences(int count) {
    return IntStream.rangeClosed(1, count)
        .mapToObj(i -> "Regulation paragraph " + i + " defines term number " + i + " precisely.")
        .reduce((a, b) -> a + " " + b)
        .orElseThrow();
  }

  @Test
  void chunksShortTextIntoSingleChunk() {
    var service = new ChunkingService(new IndexingProperties("./docs", 1000, 100, 50, 3, null));
    var doc = new Document("This is a short text that should fit into one chunk.");
    List<Document> result = service.chunkDocuments("test.txt", List.of(doc));

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().getText()).contains("short text");
  }

  @Test
  void chunksLongTextIntoMultipleChunks() {
    var service = new ChunkingService(new IndexingProperties("./docs", 100, 10, 50, 3, null));
    // Create a long text that needs multiple chunks
    String longText = "This is sentence number one. ".repeat(200);
    var doc = new Document(longText);
    List<Document> result = service.chunkDocuments("test.txt", List.of(doc));

    assertThat(result).hasSizeGreaterThan(1);
  }

  @Test
  void preservesMetadataInChunks() {
    var service = new ChunkingService(new IndexingProperties("./docs", 100, 10, 50, 3, null));
    String longText = "Word ".repeat(500);
    var doc = new Document(longText);
    doc.getMetadata().put("source", "test.txt");

    List<Document> result = service.chunkDocuments("test.txt", List.of(doc));

    assertThat(result).allSatisfy(chunk -> assertThat(chunk.getMetadata()).containsKey("source"));
  }

  @Test
  void handlesEmptyDocumentList() {
    var service = new ChunkingService(new IndexingProperties("./docs", 1000, 100, 50, 3, null));
    List<Document> result = service.chunkDocuments("empty.txt", List.of());

    assertThat(result).isEmpty();
  }

  /**
   * Issue #374: without overlap a statement that straddles a chunk boundary is cut in half, and
   * neither half carries the full claim. Every chunk after the first must therefore re-open with
   * text that already appeared at the end of its predecessor.
   */
  @Test
  void everyChunkRepeatsTheTailOfItsPredecessor() {
    var service = new ChunkingService(new IndexingProperties("./docs", 120, 30, 50, 3, null));
    var doc = new Document(distinctSentences(400));

    List<Document> result = service.chunkDocuments("regulation.md", List.of(doc));

    assertThat(result).hasSizeGreaterThan(2);
    for (int i = 1; i < result.size(); i++) {
      String previous = result.get(i - 1).getText();
      String current = result.get(i).getText();
      String openingOfCurrent = current.substring(0, Math.min(40, current.length()));
      assertThat(previous)
          .as(
              "chunk %d must re-open with text that already appeared at the end of chunk %d "
                  + "(configured overlap: 30 tokens); chunk %d starts with '%s'",
              i, i - 1, i, openingOfCurrent)
          .contains(openingOfCurrent);
    }
  }

  /** An overlap of zero must stay a hard cut — the parameter has to be able to turn itself off. */
  @Test
  void zeroOverlapProducesDisjointChunks() {
    var service = new ChunkingService(new IndexingProperties("./docs", 120, 0, 50, 3, null));
    var doc = new Document(distinctSentences(400));

    List<Document> result = service.chunkDocuments("regulation.md", List.of(doc));

    assertThat(result).hasSizeGreaterThan(2);
    for (int i = 1; i < result.size(); i++) {
      String previous = result.get(i - 1).getText();
      String openingOfCurrent = result.get(i).getText().substring(0, 40);
      assertThat(previous).doesNotContain(openingOfCurrent);
    }
  }

  @Test
  void rejectsAnOverlapThatIsNotSmallerThanTheChunkSize() {
    assertThatThrownBy(() -> new IndexingProperties("./docs", 100, 100, 50, 3, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("chunkOverlap");
  }

  @Test
  void treatsANegativeOverlapAsNoOverlap() {
    assertThat(new IndexingProperties("./docs", 1000, -1, 50, 3, null).chunkOverlap()).isZero();
  }
}
