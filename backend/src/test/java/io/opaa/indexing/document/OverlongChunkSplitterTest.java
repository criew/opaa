package io.opaa.indexing.document;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.format.chunk.ChunkAnchor;
import io.opaa.format.chunk.ChunkAnchors;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class OverlongChunkSplitterTest {

  @Test
  void theFirstPartQuotesItsPassageWithoutTheLeadTheChunkOpenedWith() {
    String lead = "Blatt: Gebühren · Tabelle: Tarife\nPosten | Betrag\n";
    String passage =
        "Verwaltungsgebühr für die Ausstellung | 50 Euro\n\n"
            + "Säumniszuschlag je angefangenen Monat bei verspäteter Zahlung der festgesetzten"
            + " Gebühr nach Ablauf der im Bescheid genannten Zahlungsfrist | 1 Prozent der"
            + " rückständigen Summe";
    Map<String, Object> metadata = new HashMap<>();
    ChunkAnchors.write(metadata, ChunkAnchor.EMPTY.withRows("Gebühren", 2L, 3L));
    ChunkAnchors.markLead(metadata, lead);
    ChunkAnchors.completed(metadata, lead + passage);

    List<Document> parts = OverlongChunkSplitter.split(new Document(lead + passage, metadata));

    assertThat(parts.get(0).getText()).startsWith(lead.strip());
    ChunkAnchor first = ChunkAnchors.read(parts.get(0).getMetadata());
    assertThat(first.quoteStart()).startsWith("Verwaltungsgebühr");
    assertThat(first.sheet()).isEqualTo("Gebühren");
    ChunkAnchor last = ChunkAnchors.read(parts.get(parts.size() - 1).getMetadata());
    assertThat(last.quoteEnd() == null ? last.quoteStart() : last.quoteEnd())
        .endsWith("rückständigen Summe");
  }
}
