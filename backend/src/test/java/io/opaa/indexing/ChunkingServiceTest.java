package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class ChunkingServiceTest {

  @Test
  void chunksShortTextIntoSingleChunk() {
    var service = new ChunkingService(new IndexingProperties("./docs", 1000, 100, 50, 3));
    var doc = new Document("This is a short text that should fit into one chunk.");
    List<Document> result = service.chunkDocuments(List.of(doc));

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().getText()).contains("short text");
  }

  @Test
  void chunksLongTextIntoMultipleChunks() {
    var service = new ChunkingService(new IndexingProperties("./docs", 100, 10, 50, 3));
    // Create a long text that needs multiple chunks
    String longText = "This is sentence number one. ".repeat(200);
    var doc = new Document(longText);
    List<Document> result = service.chunkDocuments(List.of(doc));

    assertThat(result).hasSizeGreaterThan(1);
  }

  @Test
  void preservesMetadataInChunks() {
    var service = new ChunkingService(new IndexingProperties("./docs", 100, 10, 50, 3));
    String longText = "Word ".repeat(500);
    var doc = new Document(longText);
    doc.getMetadata().put("source", "test.txt");

    List<Document> result = service.chunkDocuments(List.of(doc));

    assertThat(result).allSatisfy(chunk -> assertThat(chunk.getMetadata()).containsKey("source"));
  }

  @Test
  void handlesEmptyDocumentList() {
    var service = new ChunkingService(new IndexingProperties("./docs", 1000, 100, 50, 3));
    List<Document> result = service.chunkDocuments(List.of());

    assertThat(result).isEmpty();
  }
}
