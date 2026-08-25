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
 *
 * <p>Also carries {@code distinctExpectedDocumentSets} — the number of *distinct* expected-document
 * sets among this group's cases, as opposed to {@code n} (the raw case count). Issue #228's
 * baseline-regression tolerance (see {@code BaselineComparator}, ADR-0013) is deliberately
 * expressed per independent observation, not per case: several golden-dataset cases share an
 * identical expected-document set (e.g. every {@code crosslingual} case is the German twin of an
 * English one), so {@code n} alone overstates how many independent data points back a group's
 * average — see the report-level {@code datasetNotes} this mirrors at group granularity.
 *
 * <p><b>{@code hitCountAt5} / {@code hitCountAt10} (issue #306).</b> Counts of cases in this group
 * that scored above zero at the two ranking depths this harness measures — {@code hitCountAt5} is
 * the number of cases with {@code hitRateAt5 > 0} (a relevant document somewhere in the top 5);
 * {@code hitCountAt10} is the number of cases with a relevant document anywhere in the (at most
 * {@code searchTopK=10}-long) ranked list, which is exactly the same per-case event for {@code mrr
 * > 0}, {@code ndcgAt10 > 0} and {@code recallAt10 > 0} — the ranked list this harness scores never
 * has more than 10 entries (ADR-0012, {@code searchTopK}), so "a hit exists in the full ranked
 * list" (what makes {@code mrr} positive) and "a hit exists in the top 10" (what makes {@code
 * ndcgAt10}/{@code recallAt10} positive) are the identical event, not merely correlated. {@code
 * BaselineComparator} uses these two counts, not the four continuous means, for the group/metric
 * pairs where the mean tolerance is tighter than one case's worth of shift — see its class Javadoc.
 *
 * <p><b>{@code allExpectedDocumentsHitAt10} (issue #913).</b> Mean of {@link
 * RetrievalMetrics#allExpectedDocumentsHitAtK} — "Recall pro Teilthema" — over the group: the
 * fraction of cases where <b>every</b> expected document was retrieved, not just the fraction of
 * expected documents retrieved on average (that is what {@code recallAt10} already measures). For a
 * single-expected-document case the two metrics coincide; they diverge exactly for multi-document
 * cases (e.g. the {@code multi_topic} category), which is the point — see the field's use in
 * detecting the topK-monoculture failure mode from issue #912.
 *
 * <p>Deliberately typed {@link Double} rather than {@code double}, unlike every other metric field
 * here: a baseline file written before issue #913 (e.g. the committed {@code
 * comic-characters.json}, which this issue's scope does not require re-measuring) has no such
 * property. Jackson's record deserialization treats a missing *primitive* field as a hard error
 * (there is no sensible zero-arg default to fall back to), but tolerates a missing *reference-type*
 * field as {@code null} — the compact constructor below normalizes that {@code null} to {@code 0.0}
 * so every caller still sees a plain, never-null value.
 */
public record MetricsAggregate(
    int n,
    double hitRateAt5,
    double mrr,
    double ndcgAt10,
    double recallAt10,
    double recallAt10Ceiling,
    int distinctExpectedDocumentSets,
    int hitCountAt5,
    int hitCountAt10,
    Double allExpectedDocumentsHitAt10) {

  public MetricsAggregate {
    if (allExpectedDocumentsHitAt10 == null) {
      allExpectedDocumentsHitAt10 = 0.0;
    }
  }

  public static MetricsAggregate of(List<RetrievalMetrics.QueryResult> results) {
    if (results.isEmpty()) {
      return new MetricsAggregate(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0, 0.0);
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
    long distinctExpectedSets =
        results.stream()
            .map(r -> new java.util.TreeSet<>(r.goldenCase().expectedDocuments()))
            .distinct()
            .count();
    long hitCountAt5 = results.stream().filter(r -> r.hitRateAt5() > 0).count();
    // ndcgAt10 > 0 iff recallAt10 > 0 iff reciprocalRank > 0 for this harness (see class Javadoc)
    // — ndcgAt10 is picked arbitrarily as the representative of the three.
    long hitCountAt10 = results.stream().filter(r -> r.ndcgAt10() > 0).count();
    double allExpectedDocumentsHit =
        results.stream()
                .mapToDouble(RetrievalMetrics.QueryResult::allExpectedDocumentsHitAt10)
                .sum()
            / n;
    return new MetricsAggregate(
        n,
        hitRate,
        mrr,
        ndcg,
        recall,
        recallCeiling,
        (int) distinctExpectedSets,
        (int) hitCountAt5,
        (int) hitCountAt10,
        allExpectedDocumentsHit);
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
