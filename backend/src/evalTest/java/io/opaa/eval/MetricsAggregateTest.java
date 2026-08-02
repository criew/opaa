package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Docker-free unit tests for {@link MetricsAggregate} (issue #227 review follow-up). Run via the
 * {@code evalUnitTest} Gradle task, part of {@code check}.
 */
class MetricsAggregateTest {

  private static final double TOLERANCE = 1e-6;

  private static GoldenCase goldenCase(String category, List<String> expected) {
    return new GoldenCase("id", "test", "query", expected, category, "easy", "en", "type");
  }

  @Test
  void ofEmptyListReturnsAllZeros() {
    MetricsAggregate aggregate = MetricsAggregate.of(List.of());

    assertThat(aggregate.n()).isZero();
    assertThat(aggregate.hitRateAt5()).isEqualTo(0.0);
    assertThat(aggregate.mrr()).isEqualTo(0.0);
    assertThat(aggregate.ndcgAt10()).isEqualTo(0.0);
    assertThat(aggregate.recallAt10()).isEqualTo(0.0);
    assertThat(aggregate.recallAt10Ceiling()).isEqualTo(0.0);
  }

  @Test
  void ofAveragesMetricsAcrossQueries() {
    RetrievalMetrics.QueryResult perfect =
        RetrievalMetrics.evaluate(goldenCase("a", List.of("e")), List.of("e"));
    RetrievalMetrics.QueryResult miss =
        RetrievalMetrics.evaluate(goldenCase("a", List.of("e")), List.of("d1"));

    MetricsAggregate aggregate = MetricsAggregate.of(List.of(perfect, miss));

    assertThat(aggregate.n()).isEqualTo(2);
    assertThat(aggregate.hitRateAt5()).isCloseTo(0.5, within(TOLERANCE));
    assertThat(aggregate.mrr()).isCloseTo(0.5, within(TOLERANCE));
    assertThat(aggregate.ndcgAt10()).isCloseTo(0.5, within(TOLERANCE));
    assertThat(aggregate.recallAt10()).isCloseTo(0.5, within(TOLERANCE));
  }

  @Test
  void recallCeilingReflectsExpectedSetsLargerThanK() {
    RetrievalMetrics.QueryResult small =
        RetrievalMetrics.evaluate(goldenCase("a", List.of("e1", "e2")), List.of("e1", "e2"));
    RetrievalMetrics.QueryResult large =
        RetrievalMetrics.evaluate(
            goldenCase(
                "a",
                List.of(
                    "e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8", "e9", "e10", "e11", "e12",
                    "e13", "e14", "e15")),
            List.of("e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8", "e9", "e10"));

    MetricsAggregate aggregate = MetricsAggregate.of(List.of(small, large));

    // small: ceiling 1.0 (|E|=2 <= 10). large: ceiling 10/15. Mean of the two.
    double expectedCeiling = (1.0 + 10.0 / 15) / 2;
    assertThat(aggregate.recallAt10Ceiling()).isCloseTo(expectedCeiling, within(TOLERANCE));
  }

  @Test
  void groupByPartitionsResultsByCategory() {
    RetrievalMetrics.QueryResult inA =
        RetrievalMetrics.evaluate(goldenCase("a", List.of("e")), List.of("e"));
    RetrievalMetrics.QueryResult inB =
        RetrievalMetrics.evaluate(goldenCase("b", List.of("e")), List.of("d1"));

    Map<String, MetricsAggregate> grouped =
        MetricsAggregate.groupBy(List.of(inA, inB), GoldenCase::category);

    assertThat(grouped).containsOnlyKeys("a", "b");
    assertThat(grouped.get("a").n()).isEqualTo(1);
    assertThat(grouped.get("a").ndcgAt10()).isCloseTo(1.0, within(TOLERANCE));
    assertThat(grouped.get("b").n()).isEqualTo(1);
    assertThat(grouped.get("b").ndcgAt10()).isEqualTo(0.0);
  }
}
