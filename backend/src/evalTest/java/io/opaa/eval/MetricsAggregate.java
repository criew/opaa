package io.opaa.eval;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Mean of the four ranking metrics over a group of {@link RetrievalMetrics.QueryResult}s, plus the
 * achievable Recall@10 ceiling for that group (see {@link RetrievalMetrics#recallCeilingAtK}):
 * cases whose expected-document set is larger than {@code k=10} cannot reach Recall@10=1.0 even
 * with a perfect ranking, so the raw recall figure alone understates how well retrieval is doing.
 */
public record MetricsAggregate(
    int n,
    double hitRateAt5,
    double mrr,
    double ndcgAt10,
    double recallAt10,
    double recallAt10Ceiling) {

  public static MetricsAggregate of(List<RetrievalMetrics.QueryResult> results) {
    if (results.isEmpty()) {
      return new MetricsAggregate(0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }
    int n = results.size();
    double hitRate =
        results.stream().mapToDouble(RetrievalMetrics.QueryResult::hitRateAt5).sum() / n;
    double mrr =
        results.stream().mapToDouble(RetrievalMetrics.QueryResult::reciprocalRank).sum() / n;
    double ndcg = results.stream().mapToDouble(RetrievalMetrics.QueryResult::ndcgAt10).sum() / n;
    double recall =
        results.stream().mapToDouble(RetrievalMetrics.QueryResult::recallAt10).sum() / n;
    double recallCeiling =
        results.stream()
                .mapToDouble(
                    r -> {
                      Set<String> expected = new HashSet<>(r.goldenCase().expectedDocuments());
                      return RetrievalMetrics.recallCeilingAtK(expected, RetrievalMetrics.RECALL_K);
                    })
                .sum()
            / n;
    return new MetricsAggregate(n, hitRate, mrr, ndcg, recall, recallCeiling);
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
