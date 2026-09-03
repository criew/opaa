package io.opaa.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Group summary of "how close to the window edge" a solved golden case was (issue #1151, "Benchmark
 * bildet Grenzstabilität nicht ab"): a case whose expected document sits at rank 1 and one that
 * just barely survives at the last permitted rank both count as {@code hitRateAt5 = 1.0} today —
 * indistinguishable in the committed metrics. This aggregate makes the margin ({@link
 * RetrievalMetrics#marginAtK}) visible per group, alongside {@link MetricsAggregate}/{@link
 * PipelineMetricsAggregate}, without becoming part of either: it is deliberately <b>not</b>
 * embedded in {@link Baseline}/{@link PipelineBaseline} (both reuse the two aggregates above
 * directly as their {@code groups} value) and {@link BaselineComparator}/{@link
 * PipelineBaselineComparator} never read it. A margin observation needs a period of
 * committed-report history before it can become a comparison criterion — see the PR description for
 * the rationale not to fold it into the measurement contract yet.
 *
 * @param hitRateHits cases whose {@code hitRateMarginAt5}/{@code hitRateMargin} is non-null, i.e.
 *     that count toward {@code hitCountAt5} in the group's {@link MetricsAggregate}.
 * @param meanHitRateMargin mean margin over exactly those cases; {@code 0.0} when {@code
 *     hitRateHits} is zero (no case to average, matching the empty-group convention the other
 *     aggregates use).
 * @param marginalHitRateCount how many of {@code hitRateHits} have a margin at or below {@link
 *     #MARGINAL_THRESHOLD} — solved, but only barely: one rank change from falling out of the
 *     window.
 * @param rankingHits the same count against the wider ranking window (nDCG/Recall/MRR).
 * @param meanRankingMargin the same mean against that window.
 * @param marginalRankingCount the same "barely solved" count against that window.
 */
public record MarginAggregate(
    int hitRateHits,
    double meanHitRateMargin,
    int marginalHitRateCount,
    int rankingHits,
    double meanRankingMargin,
    int marginalRankingCount) {

  /**
   * A margin at or below this many ranks counts as "knapp gelöst": the hit sits at the window's
   * last permitted rank or one before it, one adverse rank change away from becoming a miss.
   */
  public static final int MARGINAL_THRESHOLD = 1;

  private static final MarginAggregate EMPTY = new MarginAggregate(0, 0.0, 0, 0, 0.0, 0);

  public static MarginAggregate of(List<RetrievalMetrics.QueryResult> results) {
    return summarize(
        results.stream().map(RetrievalMetrics.QueryResult::hitRateMarginAt5).toList(),
        results.stream().map(RetrievalMetrics.QueryResult::rankingMarginAt10).toList());
  }

  public static MarginAggregate ofWindowed(List<RetrievalMetrics.WindowedQueryResult> results) {
    return summarize(
        results.stream().map(RetrievalMetrics.WindowedQueryResult::hitRateMargin).toList(),
        results.stream().map(RetrievalMetrics.WindowedQueryResult::rankingMargin).toList());
  }

  /** Groups the raw-vector path's results by an arbitrary key and summarizes each group. */
  public static Map<String, MarginAggregate> groupBy(
      List<RetrievalMetrics.QueryResult> results, Function<GoldenCase, String> keyFn) {
    Map<String, List<RetrievalMetrics.QueryResult>> grouped = new TreeMap<>();
    for (RetrievalMetrics.QueryResult result : results) {
      grouped.computeIfAbsent(keyFn.apply(result.goldenCase()), k -> new ArrayList<>()).add(result);
    }
    Map<String, MarginAggregate> aggregated = new TreeMap<>(Comparator.naturalOrder());
    grouped.forEach((key, group) -> aggregated.put(key, of(group)));
    return aggregated;
  }

  /** Groups the pipeline path's results by an arbitrary key and summarizes each group. */
  public static Map<String, MarginAggregate> groupByWindowed(
      List<RetrievalMetrics.WindowedQueryResult> results, Function<GoldenCase, String> keyFn) {
    Map<String, List<RetrievalMetrics.WindowedQueryResult>> grouped = new TreeMap<>();
    for (RetrievalMetrics.WindowedQueryResult result : results) {
      grouped.computeIfAbsent(keyFn.apply(result.goldenCase()), k -> new ArrayList<>()).add(result);
    }
    Map<String, MarginAggregate> aggregated = new TreeMap<>(Comparator.naturalOrder());
    grouped.forEach((key, group) -> aggregated.put(key, ofWindowed(group)));
    return aggregated;
  }

  private static MarginAggregate summarize(
      List<Integer> hitRateMargins, List<Integer> rankingMargins) {
    if (hitRateMargins.isEmpty()) {
      return EMPTY;
    }
    List<Integer> hitRateHitMargins = hitRateMargins.stream().filter(m -> m != null).toList();
    List<Integer> rankingHitMargins = rankingMargins.stream().filter(m -> m != null).toList();
    return new MarginAggregate(
        hitRateHitMargins.size(),
        mean(hitRateHitMargins),
        marginalCount(hitRateHitMargins),
        rankingHitMargins.size(),
        mean(rankingHitMargins),
        marginalCount(rankingHitMargins));
  }

  private static double mean(List<Integer> margins) {
    return margins.isEmpty()
        ? 0.0
        : margins.stream().mapToInt(Integer::intValue).average().orElse(0.0);
  }

  private static int marginalCount(List<Integer> margins) {
    return (int) margins.stream().filter(m -> m <= MARGINAL_THRESHOLD).count();
  }
}
