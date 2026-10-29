package io.opaa.format.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChunkAnchorsTest {

  @Test
  void aShortPassageIsQuotedWholeAndWhitespaceNormalized() {
    ChunkAnchor anchor =
        ChunkAnchors.withQuotes(ChunkAnchor.EMPTY, "  Die Gebühr\n\nbeträgt   50 Euro. ");

    assertThat(anchor.quoteStart()).isEqualTo("Die Gebühr beträgt 50 Euro.");
    assertThat(anchor.quoteEnd()).isNull();
  }

  @Test
  void aLongPassageIsQuotedFromBothEndsAtWordBoundaries() {
    String passage =
        "Der Antrag ist binnen vier Wochen nach Zugang des Bescheids schriftlich bei der"
            + " zuständigen Behörde einzureichen, andernfalls gilt er als nicht gestellt.";

    ChunkAnchor anchor = ChunkAnchors.withQuotes(ChunkAnchor.EMPTY, passage);

    assertThat(anchor.quoteStart())
        .hasSizeLessThanOrEqualTo(ChunkAnchors.QUOTE_LENGTH)
        .isEqualTo("Der Antrag ist binnen vier Wochen nach Zugang des Bescheids");
    assertThat(anchor.quoteEnd())
        .hasSizeLessThanOrEqualTo(ChunkAnchors.QUOTE_LENGTH)
        .isEqualTo("einzureichen, andernfalls gilt er als nicht gestellt.");
    assertThat(passage).startsWith(anchor.quoteStart()).endsWith(anchor.quoteEnd());
  }

  @Test
  void completingDropsTheLeadFromTheQuoteAndTheMetadata() {
    Map<String, Object> metadata = new HashMap<>();
    ChunkAnchors.write(metadata, ChunkAnchor.EMPTY.withPath(List.of("3 Verfahren", "3.1 Fristen")));
    ChunkAnchors.markLead(metadata, "3 Verfahren › 3.1 Fristen\n\n");

    ChunkAnchors.completed(metadata, "3 Verfahren › 3.1 Fristen\n\nDer Antrag ist einzureichen.");

    assertThat(metadata).doesNotContainKey(ChunkMetadataKeys.ANCHOR_LEAD_METADATA_KEY);
    ChunkAnchor anchor = ChunkAnchors.read(metadata);
    assertThat(anchor.path()).containsExactly("3 Verfahren", "3.1 Fristen");
    assertThat(anchor.quoteStart()).isEqualTo("Der Antrag ist einzureichen.");
  }

  @Test
  void aHeadingOnlyChunkQuotesItsHeading() {
    Map<String, Object> metadata = new HashMap<>();
    ChunkAnchors.markLead(metadata, "3.1 Fristen\n\n");

    ChunkAnchors.completed(metadata, "3.1 Fristen");

    assertThat(ChunkAnchors.read(metadata).quoteStart()).isEqualTo("3.1 Fristen");
  }

  @Test
  void theStoredMapReadsBackWhateverNumberTypeJsonProduced() {
    Map<String, Object> stored = new HashMap<>();
    stored.put("pageFrom", 3L);
    stored.put("rowFrom", 120);
    stored.put("path", List.of("Kapitel"));
    stored.put("unknown", "ignored");

    ChunkAnchor anchor = ChunkAnchor.fromMetadata(stored);

    assertThat(anchor.pageFrom()).isEqualTo(3);
    assertThat(anchor.rowFrom()).isEqualTo(120L);
    assertThat(anchor.path()).containsExactly("Kapitel");
    assertThat(ChunkAnchor.fromMetadata(anchor.toMetadata())).isEqualTo(anchor);
    assertThat(anchor.toMetadata()).doesNotContainKeys("slide", "quoteStart");
  }

  @Test
  void anEmptyAnchorIsNotStored() {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put(ChunkMetadataKeys.ANCHOR_METADATA_KEY, Map.of("slide", 1));

    ChunkAnchors.write(metadata, ChunkAnchor.EMPTY);

    assertThat(metadata).isEmpty();
    assertThat(ChunkAnchor.fromMetadata("not a map")).isEqualTo(ChunkAnchor.EMPTY);
  }
}
