package io.opaa.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The committed retrieval-quality baseline (issue #228), checked into {@code
 * eval/baseline/comic-characters.json}. Deliberately a separate, narrower schema from {@link
 * EvaluationReport} rather than reusing that record directly: a baseline is a curated, reviewed
 * artifact (fixed points + group metrics + a human-readable rationale), not a raw run dump — it
 * intentionally omits per-query detail ({@code worstQueries}/{@code allQueryResults}) that would
 * make every regenerated report a spurious diff.
 *
 * <p>See {@code eval/baseline/README.md} for the update procedure and the tolerance rationale
 * implemented in {@link BaselineComparator}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Baseline(
    int measurementContractVersion,
    FixedPoints fixedPoints,
    Map<String, MetricsAggregate> groups,
    String measuredAt,
    Provenance provenance,
    String notes) {

  /**
   * Where this baseline's numbers came from — purely documentary, never read by {@link
   * BaselineComparator} (PR #301 review: "Erwäge Provenienzfelder in der Baseline-Datei"). Lets a
   * reader trace a committed baseline back to the exact {@code evaluateRetrieval} run and PR that
   * produced it, without having to dig through git blame.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Provenance(String sourceReportRunStartedAt, String sourcePullRequest) {}

  /**
   * The values that define what was measured, as opposed to how well it scored. Any drift here
   * means "this baseline no longer applies", not "retrieval got worse" — see {@link
   * BaselineComparator#compare}, which reports the two cases with different messages on purpose
   * (ADR-0011/ADR-0012: corpus, golden dataset, embedding model and measurement contract are all
   * baseline-defining and require a deliberate re-measurement on change).
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FixedPoints(
      String embeddingModel,
      String embeddingModelDigest,
      int embeddingDimensions,
      int chunkSize,
      boolean chunkSizeMatchesApplicationDefault,
      // Issue #721, ADR-0012 Nachtrag: chunkOverlap only ever existed as report metadata before —
      // for a one-chunk-per-document domain it cannot change anything (overlap only exists between
      // chunks), but for a multi-chunk domain it is a value that changes the measurement itself, so
      // it is now a fixed point like every other measurement-contract value.
      int chunkOverlap,
      // Issue #721, ADR-0012 Nachtrag: the k-window is now explicitly document-bound — documentTopK
      // is the number of distinct documents ranking metrics are computed over, chunkTopK is the
      // similaritySearch topK actually used to reach that many after deduplication (see
      // DocumentRanking). Both are measurement-contract values, not just run metadata.
      int documentTopK,
      int chunkTopK,
      // ADR-0012, decision 3: searchTopK and the (deliberately unset) production similarity
      // threshold are part of the measurement contract, not just run metadata — see
      // BaselineComparator's Javadoc and PR #301 review, Befund 4.
      int searchTopK,
      double productionSimilarityThreshold,
      // ADR-0011, Konsequenzen: a future switch to exact search for the evaluation is a
      // measurement-contract change, same as a corpus or model change.
      String pgvectorIndexType,
      String corpusManifestSha256,
      int corpusDocumentCount,
      String goldenDatasetFile,
      String goldenDatasetSha256,
      int goldenCaseCount) {}

  /** Group keys used in {@link #groups()} — must match how the report's groups are addressed. */
  public static final String OVERALL = "overall";

  public static String category(String name) {
    return "category:" + name;
  }

  public static String difficulty(String name) {
    return "difficulty:" + name;
  }

  public static String language(String name) {
    return "language:" + name;
  }

  public static Baseline load(Path file) throws IOException {
    Baseline baseline =
        JsonMapper.builder().build().readValue(Files.readString(file), Baseline.class);
    validate(baseline, file);
    return baseline;
  }

  /**
   * Guards against a silently over-wide tolerance gate (PR #301 review, second round): {@code
   * distinctExpectedDocumentSets} is required in {@code toleranceFor}'s denominator ({@code
   * BaselineComparator}). A hand-edited baseline that drops or misspells the field would otherwise
   * deserialize it as {@code 0} (Jackson's default for a missing {@code int}), which {@code
   * Math.max(nEff, 1)} then turns into {@code 1} — silently *widening* every metric's tolerance in
   * that group to the full {@code RELATIVE_CAP_FRACTION * baselineValue}, the loosest the formula
   * can produce, instead of failing loudly. Failing fast here at load time is cheaper than
   * debugging why a group's tolerance mysteriously loosened.
   */
  private static void validate(Baseline baseline, Path file) {
    baseline
        .groups()
        .forEach(
            (key, aggregate) -> {
              if (aggregate.distinctExpectedDocumentSets() <= 0) {
                throw new IllegalStateException(
                    "Baseline group '"
                        + key
                        + "' in "
                        + file.toAbsolutePath()
                        + " has distinctExpectedDocumentSets="
                        + aggregate.distinctExpectedDocumentSets()
                        + " (missing or zero) — this would silently widen that group's tolerance "
                        + "to the loosest possible value instead of failing loudly. Fix the field "
                        + "in the baseline file.");
              }
              validateCaseCounts(key, aggregate, file);
            });
  }

  /**
   * Issue #306 review, Befund 3: the original guard only fired when the mean said "at least one
   * case scored above zero" but the recorded count was zero or missing — it caught a completely
   * absent field, but not a *wrong, still-positive* count (e.g. {@code 3} typed instead of {@code
   * 30}). Three stronger, exact-or-provably-sound checks replace it:
   *
   * <ol>
   *   <li>{@code hitCountAt5 == round(hitRateAt5 * n)} — an exact equality, not a heuristic: {@code
   *       hitRateAt5} is binary per case (see {@code RetrievalMetrics}), so its mean times {@code
   *       n} is exactly the hit count, up to the baseline file's 3-decimal rounding (which {@code
   *       Math.round} absorbs cleanly for every {@code n} in this baseline — verified against all
   *       ten committed groups in the issue #306 review).
   *   <li>{@code 0 <= hitCountAt5 <= hitCountAt10 <= n} — every top-5 hit is also a top-10 hit (the
   *       ranked list this harness scores is a single ordered list, top 5 is a prefix of top 10),
   *       and neither count can exceed the case count itself.
   *   <li>{@code hitCountAt10 >= max(mrr, ndcgAt10, recallAt10) * n} — each of these three metrics
   *       sums, across the group's cases, to at most {@code hitCountAt10} (every contributing case
   *       scores at most 1.0, see {@code RetrievalMetrics}), so the count can never be *smaller*
   *       than any of the three means times {@code n}. A small epsilon absorbs the same 3-decimal
   *       rounding as above.
   * </ol>
   *
   * <p>Issue #913 review: two more invariants guard {@code allExpectedDocumentsHitAt10} — a
   * dropped/stale field silently becomes {@code null} → {@code 0.0} (see {@link MetricsAggregate}'s
   * compact constructor) and would otherwise pass every check above unnoticed: {@code
   * allExpectedDocumentsHitAt10 <= recallAt10 + ε} (a case counting toward the former also fully
   * counts toward the latter) and {@code allExpectedDocumentsHitAt10 * n <= hitCountAt10 + ε} (same
   * reasoning as the {@code hitCountAt10} bound above).
   */
  private static void validateCaseCounts(String key, MetricsAggregate aggregate, Path file) {
    long expectedHitCountAt5 = Math.round(aggregate.hitRateAt5() * aggregate.n());
    if (aggregate.hitCountAt5() != expectedHitCountAt5) {
      throw new IllegalStateException(
          "Baseline group '"
              + key
              + "' in "
              + file.toAbsolutePath()
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
        || aggregate.hitCountAt5() > aggregate.hitCountAt10()
        || aggregate.hitCountAt10() > aggregate.n()) {
      throw new IllegalStateException(
          "Baseline group '"
              + key
              + "' in "
              + file.toAbsolutePath()
              + " violates 0 <= hitCountAt5 <= hitCountAt10 <= n (hitCountAt5="
              + aggregate.hitCountAt5()
              + ", hitCountAt10="
              + aggregate.hitCountAt10()
              + ", n="
              + aggregate.n()
              + ") — every top-5 hit is also a top-10 hit, and neither count can exceed the case "
              + "count. Fix the field(s) in the baseline file.");
    }
    // Rounding epsilon: the baseline stores means rounded to 3 decimals, so the implied sum can
    // be off by up to n * 0.0005 (well under 0.1 for every group size in this baseline).
    double impliedMinimumHits =
        Math.max(aggregate.mrr(), Math.max(aggregate.ndcgAt10(), aggregate.recallAt10()))
            * aggregate.n();
    if (aggregate.hitCountAt10() + 0.1 < impliedMinimumHits) {
      throw new IllegalStateException(
          "Baseline group '"
              + key
              + "' in "
              + file.toAbsolutePath()
              + " has hitCountAt10="
              + aggregate.hitCountAt10()
              + ", too small for mrr/ndcgAt10/recallAt10 over n="
              + aggregate.n()
              + " (each case contributes at most 1.0 to every one of those three metrics, so their "
              + "sum can never exceed hitCountAt10) — fix the field(s) in the baseline file.");
    }
    // Issue #913 review: guards against a silently vanished allExpectedDocumentsHitAt10 (missing
    // field deserializes as null, normalized to 0.0 by MetricsAggregate — see this method's
    // Javadoc). A true 0.0 (e.g. a group with no multi-document cases) always satisfies both
    // inequalities below, so neither check ever fires on a legitimately absent metric.
    double allTopicsHit = aggregate.allExpectedDocumentsHitAt10();
    double roundingEpsilon = 0.0005 * aggregate.n() + 1e-9;
    if (allTopicsHit > aggregate.recallAt10() + roundingEpsilon) {
      throw new IllegalStateException(
          "Baseline group '"
              + key
              + "' in "
              + file.toAbsolutePath()
              + " has allExpectedDocumentsHitAt10="
              + allTopicsHit
              + " but recallAt10="
              + aggregate.recallAt10()
              + " — a case only counts toward the former if it also fully counts toward the "
              + "latter (RetrievalMetrics#allExpectedDocumentsHitAtK), so the former can never "
              + "exceed the latter. Fix the field(s) in the baseline file.");
    }
    if (allTopicsHit * aggregate.n() > aggregate.hitCountAt10() + roundingEpsilon) {
      throw new IllegalStateException(
          "Baseline group '"
              + key
              + "' in "
              + file.toAbsolutePath()
              + " has allExpectedDocumentsHitAt10="
              + allTopicsHit
              + " over n="
              + aggregate.n()
              + ", too large for hitCountAt10="
              + aggregate.hitCountAt10()
              + " (every case counted by allExpectedDocumentsHitAt10 also counts toward "
              + "hitCountAt10) — fix the field(s) in the baseline file.");
    }
  }
}
