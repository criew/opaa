package io.opaa.indexing.document;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.chunk.ChunkNotEmbeddableException;
import org.junit.jupiter.api.Test;

/** The reason a document carries after a failed ingest names an oversized chunk where it can. */
class DocumentIngestFailureMessageTest {

  @Test
  void aChunkWithAFundortIsNamedByIt() {
    assertThat(
            DocumentIngestService.failureMessage(
                new ChunkNotEmbeddableException("Abschn. II. Aufbewahrungsfristen", 4, null)))
        .isEqualTo(
            "Die Datei konnte nicht verarbeitet werden: Ein Textabschnitt ist zu lang für das"
                + " Embedding-Modell (Abschn. II. Aufbewahrungsfristen)");
  }

  @Test
  void aChunkWithoutAFundortIsNamedByItsOneBasedPosition() {
    assertThat(DocumentIngestService.failureMessage(new ChunkNotEmbeddableException(null, 4, null)))
        .isEqualTo(DocumentIngestService.NOT_EMBEDDABLE_MESSAGE + " (Teil 5)");
  }

  @Test
  void anyOtherFailureKeepsTheGenericReason() {
    assertThat(DocumentIngestService.failureMessage(new IllegalStateException("store down")))
        .isEqualTo(DocumentIngestService.PROCESSING_FAILED_MESSAGE);
  }
}
