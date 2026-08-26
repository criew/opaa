package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/** Unit tests for {@link ReciprocalRankFusion#fuse}'s rank-based merge and dedup (#923). */
class ReciprocalRankFusionTest {

  private static Document chunk(String id, String text, double score) {
    return Document.builder().id(id).text(text).metadata(Map.of()).score(score).build();
  }

  @Test
  void rankOneOfEverySubQueryOutranksARankTwoEntryEvenAtAMuchHigherScore() {
    // Deliberately gives each rank-2 entry an implausibly high similarity score and each rank-1
    // entry a low one - proving the fusion is rank-based, not score-based (#923's central
    // invariant: scores from different search vectors are not comparable). Distinct ids for the
    // two rank-2 entries so their contributions do not accumulate into a single chunk (see
    // #aChunkAppearingInEveryListOutranksOneAppearingInOnlyOne for that scenario instead).
    Document a = chunk("a", "topic A top hit", 0.1);
    Document bInTopicA = chunk("b1", "topic A second hit", 0.99);
    Document c = chunk("c", "topic B top hit", 0.1);
    Document bInTopicB = chunk("b2", "topic B second hit", 0.99);

    List<Document> topicAResults = List.of(a, bInTopicA);
    List<Document> topicBResults = List.of(c, bInTopicB);

    List<Document> fused = ReciprocalRankFusion.fuse(List.of(topicAResults, topicBResults), 10);

    assertThat(fused).extracting(Document::getId).containsExactly("a", "c", "b1", "b2");
  }

  @Test
  void aChunkAppearingInEveryListOutranksOneAppearingInOnlyOne() {
    Document sharedRankTwo = chunk("shared", "appears in both sub-queries, rank 2 each time", 0.5);
    Document onlyInFirst = chunk("only-first", "rank 1 in its own sub-query only", 0.5);
    Document onlyInSecond = chunk("only-second", "rank 1 in its own sub-query only", 0.5);

    List<Document> first = List.of(onlyInFirst, sharedRankTwo);
    List<Document> second = List.of(onlyInSecond, sharedRankTwo);

    List<Document> fused = ReciprocalRankFusion.fuse(List.of(first, second), 10);

    // "shared" accumulates two rank-2 contributions (1/62 + 1/62 ≈ 0.0323), edging out each
    // individually-rank-1 chunk's single contribution (1/61 ≈ 0.0164).
    assertThat(fused)
        .extracting(Document::getId)
        .containsExactly("shared", "only-first", "only-second");
  }

  @Test
  void dedupesByChunkIdKeepingTheHigherScoringDocumentInstance() {
    Document lowerScoreFirst = chunk("dup", "first list's copy", 0.1);
    Document higherScoreSecond = chunk("dup", "second list's copy", 0.9);

    List<Document> fused =
        ReciprocalRankFusion.fuse(
            List.of(List.of(lowerScoreFirst), List.of(higherScoreSecond)), 10);

    assertThat(fused).hasSize(1);
    assertThat(fused.getFirst().getText()).isEqualTo("second list's copy");
  }

  @Test
  void resultIsCappedAtTheOverallBudget() {
    List<Document> ranked =
        List.of(
            chunk("a", "a", 0.4), chunk("b", "b", 0.3), chunk("c", "c", 0.2), chunk("d", "d", 0.1));

    List<Document> fused = ReciprocalRankFusion.fuse(List.of(ranked), 2);

    assertThat(fused).extracting(Document::getId).containsExactly("a", "b");
  }

  @Test
  void emptyInputYieldsAnEmptyList() {
    assertThat(ReciprocalRankFusion.fuse(List.of(), 10)).isEmpty();
  }

  @Test
  void nonPositiveBudgetYieldsAnEmptyList() {
    List<Document> ranked = List.of(chunk("a", "a", 0.5));

    assertThat(ReciprocalRankFusion.fuse(List.of(ranked), 0)).isEmpty();
  }
}
