package io.opaa.eval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure ranking-metric math (Hit Rate@k, MRR, nDCG@k, Recall@k) — see
 * docs/discussions/discussion-rag-evaluation.md, section 2.1, and issue #227. No I/O, no Spring:
 * kept independent of the harness so the metric definitions can be unit-tested in isolation.
 *
 * <p>All metrics are computed against a single ranked list per query (deduplicated by document file
 * name, best rank kept), evaluated to a fixed maximum depth. Relevance is binary: a document either
 * is or is not in {@link GoldenCase#expectedDocuments()}.
 */
public final class RetrievalMetrics {

  public static final int HIT_RATE_K = 5;
  public static final int NDCG_K = 10;
  public static final int RECALL_K = 10;

  private RetrievalMetrics() {}

  /** Per-query result: the four metrics plus the ranked list that produced them (for reporting). */
  public record QueryResult(
      GoldenCase goldenCase,
      List<String> rankedFileNames,
      double hitRateAt5,
      double reciprocalRank,
      double ndcgAt10,
      double recallAt10) {}

  public static QueryResult evaluate(GoldenCase goldenCase, List<String> rankedFileNames) {
    Set<String> expected = new LinkedHashSet<>(goldenCase.expectedDocuments());
    return new QueryResult(
        goldenCase,
        rankedFileNames,
        hitRateAtK(rankedFileNames, expected, HIT_RATE_K),
        reciprocalRank(rankedFileNames, expected),
        ndcgAtK(rankedFileNames, expected, NDCG_K),
        recallAtK(rankedFileNames, expected, RECALL_K));
  }

  static double hitRateAtK(List<String> ranked, Set<String> expected, int k) {
    return ranked.stream().limit(k).anyMatch(expected::contains) ? 1.0 : 0.0;
  }

  /** Reciprocal rank of the first relevant hit anywhere in the (fully) ranked list; 0 if none. */
  static double reciprocalRank(List<String> ranked, Set<String> expected) {
    for (int i = 0; i < ranked.size(); i++) {
      if (expected.contains(ranked.get(i))) {
        return 1.0 / (i + 1);
      }
    }
    return 0.0;
  }

  /**
   * Binary-relevance nDCG@k: DCG normalized by the ideal DCG for this query's expected-set size.
   */
  static double ndcgAtK(List<String> ranked, Set<String> expected, int k) {
    double dcg = 0.0;
    int depth = Math.min(k, ranked.size());
    for (int i = 0; i < depth; i++) {
      if (expected.contains(ranked.get(i))) {
        dcg += 1.0 / log2(i + 2);
      }
    }
    int idealHits = Math.min(k, expected.size());
    double idcg = 0.0;
    for (int i = 0; i < idealHits; i++) {
      idcg += 1.0 / log2(i + 2);
    }
    return idcg == 0.0 ? 0.0 : dcg / idcg;
  }

  static double recallAtK(List<String> ranked, Set<String> expected, int k) {
    if (expected.isEmpty()) {
      return 0.0;
    }
    long hits = ranked.stream().limit(k).filter(expected::contains).count();
    return (double) hits / expected.size();
  }

  private static double log2(double x) {
    return Math.log(x) / Math.log(2);
  }
}
