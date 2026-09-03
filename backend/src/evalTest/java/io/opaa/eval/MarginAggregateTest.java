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
    assertThat(aggregate.rankingHits()).isZero();
    assertThat(aggregate.meanRankingMargin()).isEqualTo(0.0);
    assertThat(aggregate.marginalRankingCount()).isZero();
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

    assertThat(knappMargins.hitRateHits()).isEqualTo(2);
    assertThat(knappMargins.meanHitRateMargin()).isCloseTo(0.0, within(TOLERANCE));
    assertThat(knappMargins.marginalHitRateCount())
        .as("every case in the knapp group sits at the window's last permitted rank")
        .isEqualTo(2);

    assertThat(safeMargins.meanHitRateMargin()).isGreaterThan(knappMargins.meanHitRateMargin());
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
