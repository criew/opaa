package io.opaa.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Group aggregate of the pipeline measurement path (issue #1039,
 * docs/features/retrieval-benchmark.md §1) — the counterpart of {@link MetricsAggregate} for the
 * path that measures through the production query pipeline instead of {@code
 * VectorStore.similaritySearch} directly.
 *
 * <p><b>Every component name carries its own window on purpose.</b> The two paths measure at
 * different windows — {@code documentTopK=10} for the raw-vector path, the production {@code
 * top-k=8} here — and are not interconvertible. A generically named {@code ndcg} would read as
 * comparable to the raw path's {@code ndcgAt10} the moment two numbers land in the same table,
 * which the specification calls an evaluation error outright. {@link #of} therefore also refuses
 * results measured at any other window rather than letting a component name lie.
 *
 * <p>The metric mathematics is not duplicated: {@link #of} consumes {@link
 * RetrievalMetrics.WindowedQueryResult}s produced by the same {@link RetrievalMetrics} helpers the
 * raw path uses. What is duplicated is only the aggregation shape, deliberately, so the raw path's
 * report and baseline schema stay byte-for-byte what they were.
 *
 * @param recallAt8Ceiling the highest Recall@8 this group could reach, given how many cases expect
 *     more than eight documents — same purpose as {@link MetricsAggregate#recallAt10Ceiling()}, at
 *     this path's window.
 * @param hitCountAt8 the number of cases with a relevant document anywhere in the (at most
 *     eight-entry) ranked list, i.e. the identical per-case event behind {@code mrrAt8 > 0}, {@code
 *     ndcgAt8 > 0} and {@code recallAt8 > 0}.
 */
public record PipelineMetricsAggregate(
    int n,
    double hitRateAt5,
    double mrrAt8,
    double ndcgAt8,
    double recallAt8,
    double recallAt8Ceiling,
    int distinctExpectedDocumentSets,
    int hitCountAt5,
    int hitCountAt8,
    double allExpectedDocumentsHitAt8) {

  /**
   * The Hit Rate window, unchanged from the raw-vector path (ADR-0012 decision 2): "what a user
   * sees on the first screen" is a property of the presentation, not of the retrieval path.
   */
  public static final int HIT_RATE_K = RetrievalMetrics.HIT_RATE_K;

  /**
   * The ranking window of the pipeline path: the production {@code opaa.query.top-k}, i.e. the
   * actual number of chunks a real answer is built from. The harness asserts the configured value
   * against this constant, so a changed production default fails loudly instead of silently
   * producing an {@code …At8} component holding an @-something-else number.
   */
  public static final int RANKING_K = 8;

  /** Human-readable window label carried into every report and summary. */
  public static final String METRIC_WINDOW_NOTE =
      "Hit Rate@5, MRR@8, nDCG@8, Recall@8 — das Fenster des Pipeline-Pfads ist die tatsächliche "
          + "Trefferzahl der Produktion (opaa.query.top-k=8). Diese Zahlen sind nicht mit den "
          + "@10-Zahlen des Rohvektor-Pfads (documentTopK=10, ohne Ähnlichkeitsschwelle) "
          + "vergleichbar und dürfen nicht ohne Fensterangabe nebeneinandergestellt werden "
          + "(docs/features/retrieval-benchmark.md, Abschnitt 1).";

  public static PipelineMetricsAggregate of(List<RetrievalMetrics.WindowedQueryResult> results) {
    requireExpectedWindows(results);
    if (results.isEmpty()) {
      return new PipelineMetricsAggregate(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0, 0.0);
    }
    int n = results.size();
    double hitRate =
        results.stream().mapToDouble(RetrievalMetrics.WindowedQueryResult::hitRate).sum() / n;
    double mrr =
        results.stream().mapToDouble(RetrievalMetrics.WindowedQueryResult::reciprocalRank).sum()
            / n;
    double ndcg =
        results.stream().mapToDouble(RetrievalMetrics.WindowedQueryResult::ndcg).sum() / n;
    double recall =
        results.stream().mapToDouble(RetrievalMetrics.WindowedQueryResult::recall).sum() / n;
    double recallCeiling =
        results.stream()
                .mapToDouble(
                    r -> {
                      Set<String> expected = new HashSet<>(r.goldenCase().expectedDocuments());
                      return RetrievalMetrics.recallCeilingAtK(expected, RANKING_K);
                    })
                .sum()
            / n;
    long distinctExpectedSets =
        results.stream()
            .map(r -> new TreeSet<>(r.goldenCase().expectedDocuments()))
            .distinct()
            .count();
    long hitCountAt5 = results.stream().filter(r -> r.hitRate() > 0).count();
    long hitCountAt8 = results.stream().filter(r -> r.ndcg() > 0).count();
    double allExpectedDocumentsHit =
        results.stream()
                .mapToDouble(RetrievalMetrics.WindowedQueryResult::allExpectedDocumentsHit)
                .sum()
            / n;
    return new PipelineMetricsAggregate(
        n,
        hitRate,
        mrr,
        ndcg,
        recall,
        recallCeiling,
        (int) distinctExpectedSets,
        (int) hitCountAt5,
        (int) hitCountAt8,
        allExpectedDocumentsHit);
  }

  /** Groups results by an arbitrary key (category, difficulty, language, …) and aggregates each. */
  public static Map<String, PipelineMetricsAggregate> groupBy(
      List<RetrievalMetrics.WindowedQueryResult> results, Function<GoldenCase, String> keyFn) {
    Map<String, List<RetrievalMetrics.WindowedQueryResult>> grouped = new TreeMap<>();
    for (RetrievalMetrics.WindowedQueryResult result : results) {
      grouped.computeIfAbsent(keyFn.apply(result.goldenCase()), k -> new ArrayList<>()).add(result);
    }
    Map<String, PipelineMetricsAggregate> aggregated = new TreeMap<>(Comparator.naturalOrder());
    grouped.forEach((key, group) -> aggregated.put(key, of(group)));
    return aggregated;
  }

  /**
   * Guards the promise this record's component names make: a result measured at any other window
   * would be reported under an {@code …At8}/{@code …At5} name that does not describe it.
   */
  private static void requireExpectedWindows(List<RetrievalMetrics.WindowedQueryResult> results) {
    for (RetrievalMetrics.WindowedQueryResult result : results) {
      if (result.hitRateK() != HIT_RATE_K || result.rankingK() != RANKING_K) {
        throw new IllegalArgumentException(
            "PipelineMetricsAggregate names its components after the windows "
                + HIT_RATE_K
                + "/"
                + RANKING_K
                + ", but case '"
                + result.goldenCase().id()
                + "' was measured at hitRateK="
                + result.hitRateK()
                + " rankingK="
                + result.rankingK());
      }
    }
  }
}
