package io.opaa.query.citation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/** Repair of malformed citation markers before the answer is evaluated and stored (#2298). */
class CitationMarkerRepairTest {

  private static Document chunk(String documentId, String fileName, int chunkIndex) {
    return Document.builder()
        .text("Inhalt")
        .metadata(
            Map.of("document_id", documentId, "file_name", fileName, "chunk_index", chunkIndex))
        .build();
  }

  private static final List<Document> CHUNKS =
      List.of(
          chunk("doc-1", "05_schulung-dateiformate.pptx", 2),
          chunk("doc-2", "06_intranet-formathinweise.html", 1));

  @Test
  void aFileNameInTheDocumentIdSlotIsRewrittenToTheCanonicalMarker() {
    String answer =
        "Nur aufgeführte Formate. "
            + "【source: 05_schulung-dateiformate.pptx#2 | 05_schulung-dateiformate.pptx】";

    assertThat(CitationMarkerRepair.repair(answer, CHUNKS))
        .isEqualTo("Nur aufgeführte Formate. 【source: doc-1#2 | 05_schulung-dateiformate.pptx】");
  }

  @Test
  void theFileNameIsMatchedIgnoringCaseAndKeepsTheStoredSpelling() {
    String answer = "Text 【source: 06_INTRANET-Formathinweise.html#1】";

    assertThat(CitationMarkerRepair.repair(answer, CHUNKS))
        .isEqualTo("Text 【source: doc-2#1 | 06_intranet-formathinweise.html】");
  }

  @Test
  void aWellFormedMarkerIsLeftUntouchedEvenWhenItPointsAtNothingRetrieved() {
    String answer = "Text 【source: doc-9#4 | fremd.pdf】 und 【source: doc-1#2 | x.pptx】.";

    assertThat(CitationMarkerRepair.repair(answer, CHUNKS)).isEqualTo(answer);
  }

  @Test
  void aFileNameSharedByTwoRetrievedDocumentsIsNotGuessedButRemoved() {
    List<Document> chunks =
        List.of(chunk("doc-1", "protokoll.md", 0), chunk("doc-2", "protokoll.md", 0));

    assertThat(CitationMarkerRepair.repair("Text. 【source: protokoll.md#0 | protokoll.md】", chunks))
        .isEqualTo("Text.");
  }

  @Test
  void aFileNameNotAmongTheRetrievedChunksIsRemoved() {
    assertThat(
            CitationMarkerRepair.repair("Text. 【source: unbekannt.pdf#0 | unbekannt.pdf】", CHUNKS))
        .isEqualTo("Text.");
  }

  @Test
  void aChunkIndexThatWasNotRetrievedForThatDocumentIsRemoved() {
    String answer =
        "Text. 【source: 05_schulung-dateiformate.pptx#7 | 05_schulung-dateiformate.pptx】";

    assertThat(CitationMarkerRepair.repair(answer, CHUNKS)).isEqualTo("Text.");
  }

  @Test
  void aLabelNamingAnotherFileIsRemoved() {
    String answer =
        "Text. 【source: 05_schulung-dateiformate.pptx#2 | 06_intranet-formathinweise.html】";

    assertThat(CitationMarkerRepair.repair(answer, CHUNKS)).isEqualTo("Text.");
  }

  @Test
  void aMarkerWithoutChunkIndexIsRemovedWithoutLeavingADoubleSpace() {
    String answer =
        "Der Satz 【source: 05_schulung-dateiformate.pptx#05_schulung-dateiformate.pptx】 geht"
            + " weiter.";

    assertThat(CitationMarkerRepair.repair(answer, CHUNKS)).isEqualTo("Der Satz geht weiter.");
  }

  @Test
  void aRemovedMarkerNextToAWellFormedOneKeepsTheWellFormedOne() {
    String answer = "Text 【source: doc-1#2 | a.pptx】【source: kaputt】.";

    assertThat(CitationMarkerRepair.repair(answer, CHUNKS))
        .isEqualTo("Text 【source: doc-1#2 | a.pptx】.");
  }

  @Test
  void aTextWithoutMarkersIsReturnedUnchanged() {
    String answer = "Kein Beleg nötig.\n\n- Punkt  \n- Punkt";

    assertThat(CitationMarkerRepair.repair(answer, CHUNKS)).isSameAs(answer);
    assertThat(CitationMarkerRepair.repair(null, CHUNKS)).isNull();
  }
}
