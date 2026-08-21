package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChunkMapTest {

  @Test
  void locatesEachChunkAtItsCharacterOffset() {
    String text = "AAAAABBBBBCCCCC";
    List<String> chunks = List.of("AAAAA", "BBBBB", "CCCCC");

    var map = ChunkMap.build("doc.md", text, chunks, Map.of());

    assertThat(map.fileName()).isEqualTo("doc.md");
    assertThat(map.chunkCount()).isEqualTo(3);
    assertThat(map.chunks()).extracting(ChunkMap.ChunkEntry::startChar).containsExactly(0, 5, 10);
    assertThat(map.chunks()).extracting(ChunkMap.ChunkEntry::endChar).containsExactly(5, 10, 15);
  }

  @Test
  void searchesForwardSoRepeatedTextDoesNotCollapseToTheFirstOccurrence() {
    String text = "same textsame textDIFFERENT";
    List<String> chunks = List.of("same text", "same text", "DIFFERENT");

    var map = ChunkMap.build("doc.md", text, chunks, Map.of());

    assertThat(map.chunks()).extracting(ChunkMap.ChunkEntry::startChar).containsExactly(0, 9, 18);
  }

  @Test
  void locatesTheChunkContainingAnAnswerSpan() {
    List<String> chunks =
        List.of("intro paragraph", "the Eiffel Tower was built in 1889", "footer");

    var map =
        ChunkMap.build(
            "eiffel-tower.md", String.join("", chunks), chunks, Map.of("case-1", "built in 1889"));

    assertThat(map.answerSpanChunkIndexByCaseId()).containsEntry("case-1", 1);
  }

  @Test
  void omitsACaseWhoseSpanIsNotFoundInAnyChunkOfThisDocument() {
    List<String> chunks = List.of("chunk a", "chunk b");

    var map =
        ChunkMap.build(
            "other.md", String.join("", chunks), chunks, Map.of("case-1", "not present here"));

    assertThat(map.answerSpanChunkIndexByCaseId()).doesNotContainKey("case-1");
  }

  @Test
  void ignoresNullOrBlankAnswerSpans() {
    List<String> chunks = List.of("chunk a");
    Map<String, String> spans = new java.util.HashMap<>();
    spans.put("case-1", null);
    spans.put("case-2", "   ");

    var map = ChunkMap.build("doc.md", "chunk a", chunks, spans);

    assertThat(map.answerSpanChunkIndexByCaseId()).isEmpty();
  }
}
