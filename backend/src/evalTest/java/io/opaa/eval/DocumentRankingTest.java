package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentRankingTest {

  @Test
  void windowSizeIsDocumentTopKTimesMaxChunksPerDocument() {
    assertThat(DocumentRanking.documentTopKWindowSize(10, 1)).isEqualTo(10);
    assertThat(DocumentRanking.documentTopKWindowSize(10, 8)).isEqualTo(80);
  }

  @Test
  void windowSizeRejectsNonPositiveArguments() {
    assertThatThrownBy(() -> DocumentRanking.documentTopKWindowSize(0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DocumentRanking.documentTopKWindowSize(10, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void dedupeKeepsFirstOccurrenceRank() {
    List<String> chunks = List.of("doc-a", "doc-b", "doc-a", "doc-c", "doc-b");

    assertThat(DocumentRanking.dedupeToDocuments(chunks))
        .containsExactly("doc-a", "doc-b", "doc-c");
  }

  @Test
  void dedupeIgnoresNullFileNames() {
    List<String> chunks = new java.util.ArrayList<>(List.of("doc-a"));
    chunks.add(null);
    chunks.add("doc-b");

    assertThat(DocumentRanking.dedupeToDocuments(chunks)).containsExactly("doc-a", "doc-b");
  }

  @Test
  void oneChunkPerDocumentMakesChunkBoundAndDocumentBoundWindowsIdentical() {
    // comic-characters (ADR-0010): with the Ein-Chunk-Invariante holding, every chunk is a
    // different document, so deduplication changes nothing — this is the mechanism behind the
    // PR's "bit-identical baseline" claim, exercised as a unit test rather than only asserted in
    // prose.
    List<String> tenDistinctChunks =
        List.of(
            "doc-1", "doc-2", "doc-3", "doc-4", "doc-5", "doc-6", "doc-7", "doc-8", "doc-9",
            "doc-10");

    var result = DocumentRanking.applyDocumentWindow(tenDistinctChunks, 10);

    assertThat(result.rankedFileNames()).isEqualTo(tenDistinctChunks);
    assertThat(result.distinctDocumentsReached()).isEqualTo(10);
    assertThat(result.reachedDocumentTopK()).isTrue();
  }

  @Test
  void multiChunkDocumentsCanStarveTheOldChunkBoundWindow() {
    // Ten chunks, but only 3 distinct documents because one document (doc-big) supplies 8 of
    // them — exactly the failure mode issue #721 describes: a naive similaritySearch(topK=10)
    // measures across 3 documents, not 10, without anything flagging it.
    List<String> tenChunksThreeDocuments =
        List.of(
            "doc-big", "doc-big", "doc-big", "doc-big", "doc-big", "doc-big", "doc-big", "doc-big",
            "doc-2", "doc-3");

    var narrowWindow = DocumentRanking.applyDocumentWindow(tenChunksThreeDocuments, 10);

    assertThat(narrowWindow.distinctDocumentsReached()).isEqualTo(3);
    assertThat(narrowWindow.reachedDocumentTopK()).isFalse();
  }

  @Test
  void widenedChunkTopKCanRecoverTheFullDocumentWindow() {
    // Same corpus shape as above, but the chunk search was sized via documentTopKWindowSize
    // beforehand (documentTopK=10, maxChunksPerDocument=8 -> chunkTopK=80), so more of the small
    // documents are visible in the chunk-ranked list before truncation.
    java.util.List<String> chunks = new java.util.ArrayList<>();
    for (int i = 0; i < 8; i++) {
      chunks.add("doc-big");
    }
    for (int i = 1; i <= 9; i++) {
      chunks.add("doc-" + i);
    }

    var widenedWindow = DocumentRanking.applyDocumentWindow(chunks, 10);

    assertThat(widenedWindow.distinctDocumentsReached()).isEqualTo(10);
    assertThat(widenedWindow.reachedDocumentTopK()).isTrue();
    assertThat(widenedWindow.rankedFileNames()).hasSize(10);
  }

  @Test
  void truncationRespectsDocumentTopKEvenWithMoreDistinctDocuments() {
    List<String> chunks = List.of("doc-1", "doc-2", "doc-3", "doc-4", "doc-5");

    var result = DocumentRanking.applyDocumentWindow(chunks, 3);

    assertThat(result.rankedFileNames()).containsExactly("doc-1", "doc-2", "doc-3");
    assertThat(result.distinctDocumentsReached()).isEqualTo(5);
    assertThat(result.reachedDocumentTopK()).isTrue();
  }
}
