package io.opaa.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Group summary of "how close to the window edge" a golden case's first relevant hit was (issue
 * #1151, "Benchmark bildet Grenzstabilität nicht ab"): a case whose expected document sits at rank
 * 1 and one that just barely survives at the last permitted rank both count as {@code hitRateAt5 =
 * 1.0} today — indistinguishable in the committed metrics. This aggregate makes the margin ({@link
 * RetrievalMetrics#marginAtK}) visible per group, alongside {@link MetricsAggregate}/{@link
 * PipelineMetricsAggregate}, without becoming part of either: it is deliberately <b>not</b>
 * embedded in {@link Baseline}/{@link PipelineBaseline} (both reuse the two aggregates above
 * directly as their {@code groups} value) and {@link BaselineComparator}/{@link
 * PipelineBaselineComparator} never read it. A margin observation needs a period of
 * committed-report history before it can become a comparison criterion — see #1206's follow-up
 * issue for when/whether that happens.
 *
 * <p><b>A margin below zero is a miss, never a hit</b> — this is the one invariant every field pair
 * here upholds. {@code hitRateHits}/{@code meanHitRateMargin}/{@code marginalHitRateCount} only
 * ever look at margins {@code >= 0}, exactly the cases that count toward {@code hitCountAt5} in the
 * group's {@link MetricsAggregate}; a case whose expected document sits far outside the window
 * (e.g. rank 20 against a 5-window, margin {@code -15}) contributes to neither. {@code
 * hitRateNarrowMissCount} is the separate, explicitly named count of cases that missed the window
 * but only barely (margin in {@code [-MARGINAL_THRESHOLD, -1]}) — the VGS/#938 case from the issue
 * (rank 6 against a 5-window, margin {@code -1}) is exactly this category, not a "knapp gelöst"
 * one.
 *
 * @param hitRateHits cases with a non-negative {@code hitRateMarginAt5}/{@code hitRateMargin} — the
 *     same population {@code hitCountAt5} counts.
 * @param meanHitRateMargin mean margin over exactly {@code hitRateHits}; {@code 0.0} when it is
 *     zero (no case to average, matching the empty-group convention the other aggregates use).
 * @param marginalHitRateCount how many of {@code hitRateHits} have a margin at or below {@link
 *     #MARGINAL_THRESHOLD} — solved, but only barely: one rank change from falling out of the
 *     window.
 * @param hitRateNarrowMissCount cases with a negative margin no smaller than {@code
 *     -MARGINAL_THRESHOLD} — missed the window, but only barely: one rank change would have saved
 *     them. Disjoint from {@code hitRateHits}.
 * @param rankingHits the same count against the wider ranking window (nDCG/Recall/MRR).
 * @param meanRankingMargin the same mean against that window.
 * @param marginalRankingCount the same "barely solved" count against that window.
 * @param rankingNarrowMissCount the same "barely missed" count against that window.
 */
public record MarginAggregate(
    int hitRateHits,
    double meanHitRateMargin,
    int marginalHitRateCount,
    int hitRateNarrowMissCount,
    int rankingHits,
    double meanRankingMargin,
    int marginalRankingCount,
    int rankingNarrowMissCount) {

  /**
   * A margin at or below this many ranks counts as "knapp gelöst" (hit) or "knapp verfehlt" (miss,
   * negated): the hit sits at the window's last permitted rank or one before it — one adverse rank
   * change away from becoming a miss; the miss sits one or {@link #MARGINAL_THRESHOLD} ranks below
   * the window edge — one favorable rank change away from becoming a hit.
   */
  public static final int MARGINAL_THRESHOLD = 1;

  private static final MarginAggregate EMPTY = new MarginAggregate(0, 0.0, 0, 0, 0, 0.0, 0, 0);

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
    Stats hitRate = stats(hitRateMargins);
    Stats ranking = stats(rankingMargins);
    return new MarginAggregate(
        hitRate.hits,
        hitRate.mean,
        hitRate.marginal,
        hitRate.narrowMiss,
        ranking.hits,
        ranking.mean,
        ranking.marginal,
        ranking.narrowMiss);
  }

  /** One window's three disjoint counts (hit / narrow miss / everything else) plus the hit mean. */
  private record Stats(int hits, double mean, int marginal, int narrowMiss) {}

  private static Stats stats(List<Integer> margins) {
    List<Integer> present = margins.stream().filter(Objects::nonNull).toList();
    List<Integer> hits = present.stream().filter(m -> m >= 0).toList();
    long marginal = hits.stream().filter(m -> m <= MARGINAL_THRESHOLD).count();
    long narrowMiss = present.stream().filter(m -> m < 0 && m >= -MARGINAL_THRESHOLD).count();
    double mean =
        hits.isEmpty() ? 0.0 : hits.stream().mapToInt(Integer::intValue).average().orElse(0.0);
    return new Stats(hits.size(), mean, (int) marginal, (int) narrowMiss);
  }
}
