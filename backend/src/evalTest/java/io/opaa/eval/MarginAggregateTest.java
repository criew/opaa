package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Docker-free unit tests for {@link MarginAggregate} (issue #1151, "Benchmark bildet
 * Grenzstabilität nicht ab"). Run via the {@code evalUnitTest} Gradle task, part of {@code check}.
 */
class MarginAggregateTest {

  private static final double TOLERANCE = 1e-6;

  private static GoldenCase goldenCase(String category, List<String> expected) {
    return new GoldenCase(
        "id", "test", "query", expected, category, "easy", "en", "type", null, null, null, null,
        null);
  }

  @Test
  void ofEmptyListReturnsAllZeros() {
    MarginAggregate aggregate = MarginAggregate.of(List.of());

    assertThat(aggregate.hitRateHits()).isZero();
    assertThat(aggregate.meanHitRateMargin()).isEqualTo(0.0);
    assertThat(aggregate.marginalHitRateCount()).isZero();
    assertThat(aggregate.hitRateNarrowMissCount()).isZero();
    assertThat(aggregate.rankingHits()).isZero();
    assertThat(aggregate.meanRankingMargin()).isEqualTo(0.0);
    assertThat(aggregate.marginalRankingCount()).isZero();
    assertThat(aggregate.rankingNarrowMissCount()).isZero();
  }

  /**
   * The core claim of issue #1151, at group granularity: a group whose {@code hitRateAt5} is a
   * perfect 1.0 in {@link MetricsAggregate} either way — because every case is a hit — can still be
   * a group of comfortable hits or a group teetering on the window edge, and {@link
   * MetricsAggregate} alone cannot tell the two apart. {@link MarginAggregate} can.
   */
  @Test
  void distinguishesAGroupOfSafeHitsFromAGroupOfKnappHitsBehindIdenticalHitRateAt5() {
    GoldenCase goldenCase = goldenCase("a", List.of("e"));
    List<RetrievalMetrics.QueryResult> safeGroup =
        List.of(
            RetrievalMetrics.evaluate(goldenCase, List.of("e", "d2", "d3", "d4", "d5")),
            RetrievalMetrics.evaluate(goldenCase, List.of("e", "d2", "d3", "d4", "d5")));
    List<RetrievalMetrics.QueryResult> knappGroup =
        List.of(
            RetrievalMetrics.evaluate(goldenCase, List.of("d1", "d2", "d3", "d4", "e")),
            RetrievalMetrics.evaluate(goldenCase, List.of("d1", "d2", "d3", "d4", "e")));

    MetricsAggregate safeMetrics = MetricsAggregate.of(safeGroup);
    MetricsAggregate knappMetrics = MetricsAggregate.of(knappGroup);
    assertThat(safeMetrics.hitRateAt5())
        .as(
            "MetricsAggregate reports both groups as fully, identically solved — the gap this "
                + "issue closes")
        .isEqualTo(knappMetrics.hitRateAt5())
        .isEqualTo(1.0);

    MarginAggregate safeMargins = MarginAggregate.of(safeGroup);
    MarginAggregate knappMargins = MarginAggregate.of(knappGroup);
    assertThat(safeMargins.hitRateHits()).isEqualTo(2);
    assertThat(safeMargins.meanHitRateMargin()).isCloseTo(4.0, within(TOLERANCE));
    assertThat(safeMargins.marginalHitRateCount())
        .as("no case in the safe group sits at or below the marginal threshold")
        .isZero();
    assertThat(safeMargins.hitRateNarrowMissCount()).isZero();

    assertThat(knappMargins.hitRateHits()).isEqualTo(2);
    assertThat(knappMargins.meanHitRateMargin()).isCloseTo(0.0, within(TOLERANCE));
    assertThat(knappMargins.marginalHitRateCount())
        .as("every case in the knapp group sits at the window's last permitted rank")
        .isEqualTo(2);
    assertThat(knappMargins.hitRateNarrowMissCount())
        .as("the knapp group is still fully solved — no case here is a miss")
        .isZero();

    assertThat(safeMargins.meanHitRateMargin()).isGreaterThan(knappMargins.meanHitRateMargin());
  }

  /**
   * Code-review finding on #1206: a group of cases whose expected document is nowhere near the
   * window (rank 20 against a 5-window, margin -15) must report {@code hitRateHits=0} and {@code
   * marginalHitRateCount=0} — not count as "knapp gelöst" the way an unbounded {@code m <=
   * MARGINAL_THRESHOLD} filter would. {@code hitCountAt5} for this group is 0 in {@link
   * MetricsAggregate}; {@code MarginAggregate} must agree, not silently report five "barely solved"
   * cases that were never solved at all.
   */
  @Test
  void deepMissesCountAsNeitherHitsNorMarginalHitsNorNarrowMisses() {
    GoldenCase goldenCase = goldenCase("a", List.of("e"));
    List<String> rankTwentyMiss =
        java.util.stream.Stream.concat(
                java.util.stream.IntStream.rangeClosed(1, 19).mapToObj(i -> "d" + i),
                java.util.stream.Stream.of("e"))
            .toList();
    List<RetrievalMetrics.QueryResult> deepMisses =
        List.of(
            RetrievalMetrics.evaluate(goldenCase, rankTwentyMiss),
            RetrievalMetrics.evaluate(goldenCase, rankTwentyMiss),
            RetrievalMetrics.evaluate(goldenCase, rankTwentyMiss),
            RetrievalMetrics.evaluate(goldenCase, rankTwentyMiss),
            RetrievalMetrics.evaluate(goldenCase, rankTwentyMiss));

    assertThat(MetricsAggregate.of(deepMisses).hitCountAt5())
        .as(
            "none of the five cases is a Hit Rate@5 hit — the ground truth this aggregate must "
                + "agree with")
        .isZero();

    MarginAggregate margins = MarginAggregate.of(deepMisses);
    assertThat(margins.hitRateHits()).isZero();
    assertThat(margins.meanHitRateMargin()).isEqualTo(0.0);
    assertThat(margins.marginalHitRateCount())
        .as("a margin of -15 must not be counted as 'knapp gelöst'")
        .isZero();
    assertThat(margins.hitRateNarrowMissCount())
        .as("a margin of -15 is far outside the narrow-miss band too")
        .isZero();
  }

  /**
   * The VGS/#938 case from the issue in miniature: a hit one rank below the window (margin -1) is a
   * "knapp verfehlt" case, disjoint from both the hit counts and the deep-miss case above — its
   * own, separately named category rather than folded into either.
   */
  @Test
  void aMarginOfMinusOneIsCountedAsANarrowMissNotAsAHit() {
    GoldenCase goldenCase = goldenCase("a", List.of("e"));
    RetrievalMetrics.QueryResult rankSixOfFive =
        RetrievalMetrics.evaluate(
            goldenCase, List.of("d1", "d2", "d3", "d4", "d5", "e", "d7", "d8", "d9", "d10"));

    MarginAggregate margins = MarginAggregate.of(List.of(rankSixOfFive));

    assertThat(rankSixOfFive.hitRateMarginAt5()).isEqualTo(-1);
    assertThat(margins.hitRateHits()).isZero();
    assertThat(margins.marginalHitRateCount()).isZero();
    assertThat(margins.hitRateNarrowMissCount())
        .as("rank 6 against a 5-window is one rank change away from being a hit")
        .isEqualTo(1);
  }

  @Test
  void groupByAggregatesEachCategorySeparately() {
    List<RetrievalMetrics.QueryResult> results =
        List.of(
            RetrievalMetrics.evaluate(
                goldenCase("a", List.of("e")), List.of("e", "d2", "d3", "d4", "d5")),
            RetrievalMetrics.evaluate(
                goldenCase("b", List.of("e")), List.of("d1", "d2", "d3", "d4", "e")));

    Map<String, MarginAggregate> byCategory =
        MarginAggregate.groupBy(results, GoldenCase::category);

    assertThat(byCategory.get("a").meanHitRateMargin()).isCloseTo(4.0, within(TOLERANCE));
    assertThat(byCategory.get("b").meanHitRateMargin()).isCloseTo(0.0, within(TOLERANCE));
  }

  @Test
  void ofWindowedUsesThePipelinePathsMarginFields() {
    GoldenCase goldenCase = goldenCase("a", List.of("e"));
    RetrievalMetrics.WindowedQueryResult safe =
        RetrievalMetrics.evaluateAt(goldenCase, List.of("e", "d2", "d3"), 5, 8);
    RetrievalMetrics.WindowedQueryResult knapp =
        RetrievalMetrics.evaluateAt(goldenCase, List.of("d1", "d2", "d3", "d4", "e"), 5, 8);

    MarginAggregate aggregate = MarginAggregate.ofWindowed(List.of(safe, knapp));

    assertThat(aggregate.hitRateHits()).isEqualTo(2);
    assertThat(aggregate.meanHitRateMargin()).isCloseTo((4.0 + 0.0) / 2, within(TOLERANCE));
  }
}
