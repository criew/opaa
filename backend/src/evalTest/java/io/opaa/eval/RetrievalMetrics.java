package io.opaa.eval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure ranking-metric math (Hit Rate@k, MRR, nDCG@k, Recall@k) — see
 * docs/discussions/discussion-rag-evaluation.md, section 2.1, and issue #227. No I/O, no Spring:
 * kept independent of the harness so the metric definitions can be unit-tested in isolation — see
 * {@code RetrievalMetricsTest}, run via the Docker-free {@code evalUnitTest} Gradle task (wired
 * into {@code check}), not the Docker-requiring {@code evaluateRetrieval} task.
 *
 * <p>Deliberately kept in the {@code evalTest} source set rather than {@code main}: this is
 * evaluation tooling, not something OPAA needs at runtime, and it must not ship in the production
 * jar (see build.gradle.kts and eval/README.md, "Unit-Tests für die Metrikmathematik").
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

  /**
   * Per-query result: the four ranking metrics plus a fifth, binary field ({@code
   * allExpectedDocumentsHitAt10}, issue #913) and the ranked list that produced them (for
   * reporting).
   */
  public record QueryResult(
      GoldenCase goldenCase,
      List<String> rankedFileNames,
      double hitRateAt5,
      double reciprocalRank,
      double ndcgAt10,
      double recallAt10,
      double allExpectedDocumentsHitAt10) {}

  public static QueryResult evaluate(GoldenCase goldenCase, List<String> rankedFileNames) {
    Set<String> expected = new LinkedHashSet<>(goldenCase.expectedDocuments());
    return new QueryResult(
        goldenCase,
        rankedFileNames,
        hitRateAtK(rankedFileNames, expected, HIT_RATE_K),
        reciprocalRank(rankedFileNames, expected),
        ndcgAtK(rankedFileNames, expected, NDCG_K),
        recallAtK(rankedFileNames, expected, RECALL_K),
        allExpectedDocumentsHitAtK(rankedFileNames, expected, RECALL_K));
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

  /**
   * "Recall pro Teilthema" (issue #913, following up on issue #912's topK-monoculture finding): 1.0
   * only if <b>every</b> expected document is somewhere in the top-{@code k} ranked list, 0.0
   * otherwise — as opposed to {@link #recallAtK}, which gives partial credit for a multi-document
   * case (e.g. 0.5 when only one of two expected documents is retrieved). That partial credit is
   * exactly the wrong shape for a {@code multi_topic} golden case: a query naming two entities from
   * two different documents (e.g. "was kosten führerschein und personalausweis") is only actually
   * answerable if the retrieved context covers both — a case where the dominant topic crowds out
   * the other must score 0, not a comforting 0.5. An empty expected set (never produced by this
   * harness's golden cases, but defensively handled the same way as the other metrics above) scores
   * 0.0, matching {@link #recallAtK}'s convention rather than the vacuously-true "all zero elements
   * are present".
   */
  static double allExpectedDocumentsHitAtK(List<String> ranked, Set<String> expected, int k) {
    if (expected.isEmpty()) {
      return 0.0;
    }
    Set<String> topK = new LinkedHashSet<>(ranked.subList(0, Math.min(k, ranked.size())));
    return topK.containsAll(expected) ? 1.0 : 0.0;
  }

  /**
   * The highest Recall@k a query could possibly achieve, given how many documents are actually
   * expected. When {@code |expected| > k}, even a perfect ranking cannot reach 1.0 — see the
   * "Recall@10-Obergrenze" note in the eval README. Returns 1.0 when the query is achievable.
   */
  public static double recallCeilingAtK(Set<String> expected, int k) {
    if (expected.isEmpty()) {
      return 0.0;
    }
    return (double) Math.min(k, expected.size()) / expected.size();
  }

  private static double log2(double x) {
    return Math.log(x) / Math.log(2);
  }
}
