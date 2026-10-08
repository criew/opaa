package io.opaa.format.shared;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.format.chunk.ChunkMetadataKeys;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class RepeatingHeaderChunkTest {

  @Test
  void buildsAChunkCarryingTheGivenLocation() {
    List<Document> chunks = RepeatingHeaderChunk.of("Kopf-/Fußzeile", "Stadt Musterstadt");

    assertThat(chunks).hasSize(1);
    Document chunk = chunks.getFirst();
    assertThat(chunk.getText()).isEqualTo("Stadt Musterstadt");
    assertThat(chunk.getMetadata().get(ChunkMetadataKeys.LOCATION_METADATA_KEY))
        .isEqualTo("Kopf-/Fußzeile");
  }

  @Test
  void nullTextYieldsNoChunk() {
    assertThat(RepeatingHeaderChunk.of("Kopf-/Fußzeile", null)).isEmpty();
  }

  @Test
  void blankTextYieldsNoChunk() {
    assertThat(RepeatingHeaderChunk.of("Kopf-/Fußzeile", "   ")).isEmpty();
  }

  @Test
  void textWithoutAnySingleLetterYieldsNoChunk() {
    // regression guard for #1145: a field's cached value (e.g. a page
    // number) can slip past a caller's own field filtering; a chunk of nothing but digits is noise.
    assertThat(RepeatingHeaderChunk.of("Kopf-/Fußzeile", "1")).isEmpty();
    assertThat(RepeatingHeaderChunk.of("Kopf-/Fußzeile", "12 / 34")).isEmpty();
  }

  @Test
  void textWithAtLeastOneLetterYieldsAChunk() {
    List<Document> chunks = RepeatingHeaderChunk.of("Kopf-/Fußzeile", "Seite 1 von 12");

    assertThat(chunks).extracting(Document::getText).containsExactly("Seite 1 von 12");
  }

  @Test
  void aHeaderBeyondTheHardLimitIsSplitAndEveryPartKeepsTheLocation() {
    String text = "Amt fuer Ordnung und Sicherheit, Zimmer 12. ".repeat(400);

    List<Document> chunks = RepeatingHeaderChunk.of("Kopf-/Fußzeile", text);

    assertThat(chunks)
        .hasSizeGreaterThan(1)
        .allSatisfy(
            chunk -> {
              assertThat(chunk.getText().length())
                  .isLessThanOrEqualTo(HeadingSectionSplitter.HARD_CHUNK_CHAR_LIMIT);
              assertThat(chunk.getMetadata().get(ChunkMetadataKeys.LOCATION_METADATA_KEY))
                  .isEqualTo("Kopf-/Fußzeile");
            });
  }
}
