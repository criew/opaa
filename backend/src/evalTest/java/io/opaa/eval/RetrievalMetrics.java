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
   * allExpectedDocumentsHitAt10}, issue #913), two margin fields (issue #1151, see {@link
   * #marginAtK}), and the ranked list that produced them (for reporting).
   *
   * @param hitRateMarginAt5 how many ranks of slack the first relevant hit had left within the Hit
   *     Rate@5 window ({@code 5 - rank}); negative once the hit falls outside that window but is
   *     still found lower in the (document-bound) ranked list. {@code null} when no expected
   *     document appears anywhere in the ranked list — the same "no hit" condition {@link
   *     #reciprocalRank} reports as {@code 0.0}. A case with a large positive margin sits
   *     comfortably inside the window; one with a margin near zero is one rank change away from
   *     falling out of it — the distinction issue #1151 exists to make visible, since {@code
   *     hitRateAt5} alone reports both as the identical {@code 1.0}.
   * @param rankingMarginAt10 the same margin against the wider ranking window (nDCG@10/Recall@10,
   *     MRR's own window). {@code null} under the same condition as {@code hitRateMarginAt5}.
   */
  public record QueryResult(
      GoldenCase goldenCase,
      List<String> rankedFileNames,
      double hitRateAt5,
      double reciprocalRank,
      double ndcgAt10,
      double recallAt10,
      double allExpectedDocumentsHitAt10,
      Integer hitRateMarginAt5,
      Integer rankingMarginAt10) {}

  public static QueryResult evaluate(GoldenCase goldenCase, List<String> rankedFileNames) {
    Set<String> expected = new LinkedHashSet<>(goldenCase.expectedDocuments());
    return new QueryResult(
        goldenCase,
        rankedFileNames,
        hitRateAtK(rankedFileNames, expected, HIT_RATE_K),
        reciprocalRank(rankedFileNames, expected),
        ndcgAtK(rankedFileNames, expected, NDCG_K),
        recallAtK(rankedFileNames, expected, RECALL_K),
        allExpectedDocumentsHitAtK(rankedFileNames, expected, RECALL_K),
        marginAtK(rankedFileNames, expected, HIT_RATE_K),
        marginAtK(rankedFileNames, expected, NDCG_K));
  }

  /**
   * Per-query result whose k-window is carried as data rather than baked into field names (issue
   * #1039) — the shape the pipeline measurement path needs, which measures at the production {@code
   * top-k}, not at the raw-vector path's {@code documentTopK=10}. Deliberately a second record next
   * to {@link QueryResult} instead of a generalization of it: {@link QueryResult}'s {@code
   * ndcgAt10}/{@code recallAt10} component names are part of the raw-vector path's report and
   * baseline schema, and renaming them would invalidate every committed baseline for a measurement
   * whose values did not change at all.
   *
   * @param hitRateK the window Hit Rate was computed at.
   * @param rankingK the window MRR, nDCG, Recall and {@code allExpectedDocumentsHit} were computed
   *     at.
   * @param hitRateMargin the margin (issue #1151, see {@link #marginAtK}) against {@code hitRateK};
   *     {@code null} when no expected document was found at all.
   * @param rankingMargin the margin against {@code rankingK}; {@code null} under the same condition
   *     as {@code hitRateMargin}.
   */
  public record WindowedQueryResult(
      GoldenCase goldenCase,
      List<String> rankedFileNames,
      int hitRateK,
      int rankingK,
      double hitRate,
      double reciprocalRank,
      double ndcg,
      double recall,
      double allExpectedDocumentsHit,
      Integer hitRateMargin,
      Integer rankingMargin) {}

  /**
   * Evaluates one case at explicitly given windows, unlike {@link #evaluate} which pins the
   * raw-vector path's 5/10/10. Identical metric mathematics — the same package-private helpers
   * below serve both — with one deliberate difference: {@link #reciprocalRankAtK} truncates at
   * {@code rankingK}, whereas {@link #reciprocalRank} scans the whole ranked list. The two coincide
   * whenever the ranked list is no longer than {@code rankingK}, which is always the case on the
   * pipeline path (its list is the production selection itself, capped at {@code top-k}).
   */
  public static WindowedQueryResult evaluateAt(
      GoldenCase goldenCase, List<String> rankedFileNames, int hitRateK, int rankingK) {
    if (hitRateK <= 0 || rankingK <= 0) {
      throw new IllegalArgumentException(
          "hitRateK and rankingK must be positive, got hitRateK="
              + hitRateK
              + " rankingK="
              + rankingK);
    }
    Set<String> expected = new LinkedHashSet<>(goldenCase.expectedDocuments());
    return new WindowedQueryResult(
        goldenCase,
        rankedFileNames,
        hitRateK,
        rankingK,
        hitRateAtK(rankedFileNames, expected, hitRateK),
        reciprocalRankAtK(rankedFileNames, expected, rankingK),
        ndcgAtK(rankedFileNames, expected, rankingK),
        recallAtK(rankedFileNames, expected, rankingK),
        allExpectedDocumentsHitAtK(rankedFileNames, expected, rankingK),
        marginAtK(rankedFileNames, expected, hitRateK),
        marginAtK(rankedFileNames, expected, rankingK));
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
   * Reciprocal rank of the first relevant hit within the top-{@code k} of the ranked list; 0 if
   * none. Unlike {@link #reciprocalRank}, which deliberately scans the whole list (ADR-0012
   * decision 1 and 2, where the list is itself the window), this bounds the scan — the honest
   * definition of "MRR@k" for a path whose ranked list can be longer than the window it reports.
   */
  static double reciprocalRankAtK(List<String> ranked, Set<String> expected, int k) {
    int depth = Math.min(k, ranked.size());
    for (int i = 0; i < depth; i++) {
      if (expected.contains(ranked.get(i))) {
        return 1.0 / (i + 1);
      }
    }
    return 0.0;
  }

  /**
   * The distance in ranks between the first relevant hit and the edge of a {@code k}-window ({@code
   * k - rank}, 1-based rank), issue #1151 ("Benchmark bildet Grenzstabilität nicht ab"): a positive
   * margin is how much room the hit has before a rank change alone would push it out of the window;
   * zero means it occupies the window's last permitted rank; negative means it already fell outside
   * that window while still appearing lower in the ranked list. {@code null} when the expected
   * document set is not found anywhere in {@code ranked} at all (mirrors {@link #reciprocalRank}
   * returning {@code 0.0} for the same condition). Deliberately a rank distance, not a score
   * distance (e.g. the fused RRF score's gap to the first displaced candidate): the ranked list
   * this harness scores carries no comparable score across its entries (ADR-0012 decision 3,
   * ranking metrics need the unfiltered rank order, not a score), so rank is the cheap,
   * deterministic quantity available without a second, score-carrying measurement path.
   *
   * <p><b>Blind spot for multi-document cases:</b> only the <b>first</b> relevant hit's rank is
   * used, mirroring {@link #reciprocalRank}. A {@code multi_topic} case whose expected set has
   * document A at rank 1 and document B at rank 10 reports a large, "safe" margin driven entirely
   * by A, even though {@code recallAt10} depends on B too and would drop the moment B is pushed one
   * rank further out. A margin of the <b>last</b> expected document reached within the window (or
   * of the weakest-margin expected document) would close this gap; not built here because no golden
   * case class needs it yet.
   */
  static Integer marginAtK(List<String> ranked, Set<String> expected, int k) {
    for (int i = 0; i < ranked.size(); i++) {
      if (expected.contains(ranked.get(i))) {
        return k - (i + 1);
      }
    }
    return null;
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
   * "Recall pro Teilthema" (issue #913): 1.0 only if <b>every</b> expected document is in the
   * top-{@code k} ranked list, 0.0 otherwise — unlike {@link #recallAtK}, which gives partial
   * credit for a multi-document case (e.g. 0.5 for one of two expected documents), the wrong shape
   * for detecting a dominant topic crowding another out of the top-K (issue #912). Empty expected
   * set scores 0.0, matching {@link #recallAtK}'s convention.
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
