package io.opaa.eval;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/** Mean of the four ranking metrics over a group of {@link RetrievalMetrics.QueryResult}s. */
public record MetricsAggregate(
    int n, double hitRateAt5, double mrr, double ndcgAt10, double recallAt10) {

  public static MetricsAggregate of(List<RetrievalMetrics.QueryResult> results) {
    if (results.isEmpty()) {
      return new MetricsAggregate(0, 0.0, 0.0, 0.0, 0.0);
    }
    int n = results.size();
    double hitRate =
        results.stream().mapToDouble(RetrievalMetrics.QueryResult::hitRateAt5).sum() / n;
    double mrr =
        results.stream().mapToDouble(RetrievalMetrics.QueryResult::reciprocalRank).sum() / n;
    double ndcg = results.stream().mapToDouble(RetrievalMetrics.QueryResult::ndcgAt10).sum() / n;
    double recall =
        results.stream().mapToDouble(RetrievalMetrics.QueryResult::recallAt10).sum() / n;
    return new MetricsAggregate(n, hitRate, mrr, ndcg, recall);
  }

  /**
   * Groups results by an arbitrary key (category, difficulty, language, ...) and aggregates each.
   */
  public static Map<String, MetricsAggregate> groupBy(
      List<RetrievalMetrics.QueryResult> results, Function<GoldenCase, String> keyFn) {
    Map<String, List<RetrievalMetrics.QueryResult>> grouped = new TreeMap<>();
    for (RetrievalMetrics.QueryResult result : results) {
      grouped
          .computeIfAbsent(keyFn.apply(result.goldenCase()), k -> new java.util.ArrayList<>())
          .add(result);
    }
    Map<String, MetricsAggregate> aggregated = new TreeMap<>(Comparator.naturalOrder());
    grouped.forEach((key, group) -> aggregated.put(key, of(group)));
    return aggregated;
  }
}
