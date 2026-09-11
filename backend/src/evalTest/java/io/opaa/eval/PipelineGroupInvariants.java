package io.opaa.eval;

import java.nio.file.Path;
import java.util.Map;

/**
 * The load-time guards every committed baseline of a {@link PipelineMetricsAggregate}-shaped group
 * map has to satisfy - shared by {@link PipelineBaseline} and {@link ConversationBaseline} rather
 * than copied into each (issue #1484): a hand-edited group whose {@code
 * distinctExpectedDocumentSets } or case counts are missing or wrong would otherwise silently widen
 * (or under-protect) that group's tolerance instead of failing loudly, and a second copy of these
 * five inequalities is how one of the two quietly stops checking one of them.
 *
 * <p>Same derivations as {@link Baseline}'s corresponding methods, at this window: {@code
 * hitCountAt8} takes {@code hitCountAt10}'s role, because the ranked list of a pipeline run never
 * has more than {@link PipelineMetricsAggregate#RANKING_K} entries, so "a hit exists in the list"
 * and "a hit exists in the top 8" are the identical event.
 */
final class PipelineGroupInvariants {

  private PipelineGroupInvariants() {}

  /**
   * @param baselineLabel how the baseline names itself in a failure message ("Pipeline baseline",
   *     "Conversation baseline") - the reader has to be told which of the two files to fix.
   */
  static void validateGroups(
      Map<String, PipelineMetricsAggregate> groups, Path file, String baselineLabel) {
    groups.forEach(
        (key, aggregate) -> {
          if (aggregate.distinctExpectedDocumentSets() <= 0) {
            throw new IllegalStateException(
                prefix(baselineLabel, key, file)
                    + " has distinctExpectedDocumentSets="
                    + aggregate.distinctExpectedDocumentSets()
                    + " (missing or zero) — this would silently widen that group's tolerance "
                    + "to the loosest possible value instead of failing loudly. Fix the field "
                    + "in the baseline file.");
          }
          validateCaseCounts(key, aggregate, file, baselineLabel);
        });
  }

  private static void validateCaseCounts(
      String key, PipelineMetricsAggregate aggregate, Path file, String baselineLabel) {
    long expectedHitCountAt5 = Math.round(aggregate.hitRateAt5() * aggregate.n());
    if (aggregate.hitCountAt5() != expectedHitCountAt5) {
      throw new IllegalStateException(
          prefix(baselineLabel, key, file)
              + " has hitCountAt5="
              + aggregate.hitCountAt5()
              + " but hitRateAt5="
              + aggregate.hitRateAt5()
              + " over n="
              + aggregate.n()
              + " implies exactly "
              + expectedHitCountAt5
              + " (hitRateAt5 is binary per case, so its mean times n is the exact hit count) — "
              + "fix the field in the baseline file.");
    }
    if (aggregate.hitCountAt5() < 0
        || aggregate.hitCountAt5() > aggregate.hitCountAt8()
        || aggregate.hitCountAt8() > aggregate.n()) {
      throw new IllegalStateException(
          prefix(baselineLabel, key, file)
              + " violates 0 <= hitCountAt5 <= hitCountAt8 <= n (hitCountAt5="
              + aggregate.hitCountAt5()
              + ", hitCountAt8="
              + aggregate.hitCountAt8()
              + ", n="
              + aggregate.n()
              + ") — every top-5 hit is also a top-8 hit, and neither count can exceed the case "
              + "count. Fix the field(s) in the baseline file.");
    }
    double impliedMinimumHits =
        Math.max(aggregate.mrrAt8(), Math.max(aggregate.ndcgAt8(), aggregate.recallAt8()))
            * aggregate.n();
    if (aggregate.hitCountAt8() + 0.1 < impliedMinimumHits) {
      throw new IllegalStateException(
          prefix(baselineLabel, key, file)
              + " has hitCountAt8="
              + aggregate.hitCountAt8()
              + ", too small for mrrAt8/ndcgAt8/recallAt8 over n="
              + aggregate.n()
              + " (each case contributes at most 1.0 to every one of those three metrics, so their "
              + "sum can never exceed hitCountAt8) — fix the field(s) in the baseline file.");
    }
    double allTopicsHit = aggregate.allExpectedDocumentsHitAt8();
    double roundingEpsilon = 0.0005 * aggregate.n() + 1e-9;
    if (allTopicsHit > aggregate.recallAt8() + roundingEpsilon) {
      throw new IllegalStateException(
          prefix(baselineLabel, key, file)
              + " has allExpectedDocumentsHitAt8="
              + allTopicsHit
              + " but recallAt8="
              + aggregate.recallAt8()
              + " — a case only counts toward the former if it also fully counts toward the "
              + "latter, so the former can never exceed the latter. Fix the field(s) in the "
              + "baseline file.");
    }
    if (allTopicsHit * aggregate.n() > aggregate.hitCountAt8() + roundingEpsilon) {
      throw new IllegalStateException(
          prefix(baselineLabel, key, file)
              + " has allExpectedDocumentsHitAt8="
              + allTopicsHit
              + " over n="
              + aggregate.n()
              + ", too large for hitCountAt8="
              + aggregate.hitCountAt8()
              + " (every case counted by allExpectedDocumentsHitAt8 also counts toward "
              + "hitCountAt8) — fix the field(s) in the baseline file.");
    }
  }

  private static String prefix(String baselineLabel, String key, Path file) {
    return baselineLabel + " group '" + key + "' in " + file.toAbsolutePath();
  }
}
