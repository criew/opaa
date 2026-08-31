package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkAnswerSpanMetricsTest {

  private static GoldenCase caseWithSpan(String span) {
    return new GoldenCase(
        "case-1",
        "cities",
        "query",
        List.of("doc.md"),
        "landmark",
        "medium",
        "de",
        "factual",
        span,
        null,
        null,
        null);
  }

  @Test
  void notApplicableWhenAnswerSpanIsNull() {
    GoldenCase goldenCase =
        new GoldenCase(
            "id",
            "comic-characters",
            "q",
            List.of("a.md"),
            "cat",
            "easy",
            "en",
            "t",
            null,
            null,
            null,
            null);

    assertThat(ChunkAnswerSpanMetrics.isApplicable(goldenCase)).isFalse();
  }

  @Test
  void notApplicableWhenAnswerSpanIsBlank() {
    assertThat(ChunkAnswerSpanMetrics.isApplicable(caseWithSpan("   "))).isFalse();
  }

  @Test
  void evaluateThrowsForANonApplicableCase() {
    GoldenCase goldenCase = caseWithSpan(null);

    assertThatThrownBy(() -> ChunkAnswerSpanMetrics.evaluate(goldenCase, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void hitRateIsOneWhenSpanAppearsWithinTopFiveChunks() {
    GoldenCase goldenCase = caseWithSpan("built in 1889");
    List<String> chunks =
        List.of("Some chunk", "Another chunk", "The tower was built in 1889 in Paris", "chunk 4");

    var result = ChunkAnswerSpanMetrics.evaluate(goldenCase, chunks);

    assertThat(result.hitRateAt5()).isEqualTo(1.0);
    assertThat(result.spanChunkRank()).isEqualTo(3);
  }

  @Test
  void hitRateIsZeroWhenSpanOnlyAppearsBeyondRankFive() {
    GoldenCase goldenCase = caseWithSpan("built in 1889");
    List<String> chunks =
        List.of("c1", "c2", "c3", "c4", "c5", "The tower was built in 1889 in Paris");

    var result = ChunkAnswerSpanMetrics.evaluate(goldenCase, chunks);

    assertThat(result.hitRateAt5()).isEqualTo(0.0);
    assertThat(result.spanChunkRank()).isEqualTo(6);
  }

  @Test
  void rankIsMinusOneWhenSpanNeverAppears() {
    GoldenCase goldenCase = caseWithSpan("built in 1889");
    List<String> chunks = List.of("c1", "c2");

    var result = ChunkAnswerSpanMetrics.evaluate(goldenCase, chunks);

    assertThat(result.hitRateAt5()).isEqualTo(0.0);
    assertThat(result.spanChunkRank()).isEqualTo(-1);
  }

  @Test
  void chunkTextChangingAcrossOverlapDoesNotBreakSpanDetection() {
    // The whole point of a literal-span ground truth: as long as the span text itself is unchanged,
    // it is found regardless of exactly where the chunk boundary around it falls.
    GoldenCase goldenCase = caseWithSpan("the Eiffel Tower was completed");
    List<String> chunksWithOverlap =
        List.of("...in 1889, the Eiffel Tower was completed after two years of...");

    var result = ChunkAnswerSpanMetrics.evaluate(goldenCase, chunksWithOverlap);

    assertThat(result.spanChunkRank()).isEqualTo(1);
  }

  @Test
  void toleratesAWhitespaceOnlyDifferenceBetweenSpanAndChunkText() {
    // Issue #721 code review, Wichtig 3: a span copied from rendered text can land on a different
    // line-wrap than the indexed chunk without being a genuinely different span.
    GoldenCase goldenCase = caseWithSpan("built in\n1889");
    List<String> chunks = List.of("the tower was   built in 1889 in Paris");

    var result = ChunkAnswerSpanMetrics.evaluate(goldenCase, chunks);

    assertThat(result.spanChunkRank()).isEqualTo(1);
  }

  @Test
  void aggregateOverEmptyResultsIsNotApplicable() {
    var aggregate = ChunkAnswerSpanMetrics.aggregate(List.of());

    assertThat(aggregate.applicableCases()).isZero();
    assertThat(aggregate.answerSpanHitRateAt5()).isNull();
    assertThat(aggregate.meanSpanRank()).isNull();
  }

  @Test
  void aggregateAveragesHitRateAndMeanRankOverFoundCasesOnly() {
    GoldenCase c1 = caseWithSpan("span one");
    GoldenCase c2 = caseWithSpan("span two");
    var r1 = ChunkAnswerSpanMetrics.evaluate(c1, List.of("span one here"));
    var r2 =
        ChunkAnswerSpanMetrics.evaluate(
            c2, List.of("nothing", "nothing", "nothing", "nothing", "nothing", "nothing"));

    var aggregate = ChunkAnswerSpanMetrics.aggregate(List.of(r1, r2));

    assertThat(aggregate.applicableCases()).isEqualTo(2);
    assertThat(aggregate.answerSpanHitRateAt5()).isEqualTo(0.5);
    // Only r1 has a found rank (1); r2's -1 is excluded from the mean.
    assertThat(aggregate.meanSpanRank()).isEqualTo(1.0);
  }
}
