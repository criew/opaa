package io.opaa.eval;

import java.util.List;

/**
 * Chunk-level retrieval metric family (issue #721, ADR-0012 Nachtrag) — deliberately separate from
 * {@link RetrievalMetrics}, not a variant of it: the two measure different things over different
 * ground truth.
 *
 * <p>{@link RetrievalMetrics} answers "did the right *document* come back" against {@code
 * expected_documents}, aggregated after {@link DocumentRanking} collapses chunks to documents. This
 * class answers "did the right *chunk* come back" against {@link GoldenCase#answerSpan()} — a
 * frozen, literal text excerpt, evaluated over the raw, undeduplicated chunk-ranked hit list.
 *
 * <p><b>Why a literal text span and not a chunk index.</b> "The answer is in chunk 3" silently
 * becomes false the moment {@code chunk-size} or {@code chunk-overlap} changes — nobody notices,
 * because nothing re-derives which chunk index the text landed in. A literal excerpt is invariant
 * under both parameters (the text itself does not move; only which chunk *contains* it can change),
 * which is exactly what lets a future comparison across chunking configurations (#374) mean
 * anything at all — see issue #721's "Bewusst kein Chunk-Index als Ground Truth".
 *
 * <p>Only applicable to cases carrying a non-null {@link GoldenCase#answerSpan()} — every
 * comic-characters case has {@code answerSpan() == null} (ADR-0010: the Ein-Chunk-Invariante makes
 * a chunk-level metric meaningless when a document never has more than one chunk to distinguish),
 * so {@link #isApplicable(GoldenCase)} and the aggregate below treat that domain as contributing
 * zero applicable cases rather than as a domain that fails this metric.
 */
public final class ChunkAnswerSpanMetrics {

  /** The window {@code answerSpanHitRateAtK} is computed over — mirrors {@code Hit Rate@5}. */
  public static final int ANSWER_SPAN_HIT_RATE_K = 5;

  private ChunkAnswerSpanMetrics() {}

  public static boolean isApplicable(GoldenCase goldenCase) {
    return goldenCase.answerSpan() != null && !goldenCase.answerSpan().isBlank();
  }

  /**
   * Per-query chunk-level result. {@code rankedChunkTexts} is the raw chunk-ranked hit list (not
   * deduplicated to documents) — the whole point of this metric family is to see which *chunk*, not
   * which document, carried the answer.
   */
  public record ChunkQueryResult(
      GoldenCase goldenCase, List<String> rankedChunkTexts, double hitRateAt5, int spanChunkRank) {}

  /**
   * Evaluates one applicable case. {@code spanChunkRank} is 1-based, matching {@link
   * RetrievalMetrics}'s rank convention; {@code -1} when the span was not found in any returned
   * chunk. Callers must check {@link #isApplicable(GoldenCase)} first — evaluating a case without
   * an {@code answerSpan} is a programming error, not a "0" result, because "no span" and "span not
   * found" are different statements.
   */
  public static ChunkQueryResult evaluate(GoldenCase goldenCase, List<String> rankedChunkTexts) {
    if (!isApplicable(goldenCase)) {
      throw new IllegalArgumentException(
          "GoldenCase '"
              + goldenCase.id()
              + "' has no answer_span — not applicable to this metric "
              + "family, check isApplicable() before calling evaluate().");
    }
    String span = goldenCase.answerSpan();
    int rank = firstSpanRank(rankedChunkTexts, span);
    double hitRateAt5 =
        rankedChunkTexts.stream()
                .limit(ANSWER_SPAN_HIT_RATE_K)
                .anyMatch(chunkText -> SpanMatcher.contains(chunkText, span))
            ? 1.0
            : 0.0;
    return new ChunkQueryResult(goldenCase, rankedChunkTexts, hitRateAt5, rank);
  }

  private static int firstSpanRank(List<String> rankedChunkTexts, String span) {
    for (int i = 0; i < rankedChunkTexts.size(); i++) {
      if (SpanMatcher.contains(rankedChunkTexts.get(i), span)) {
        return i + 1;
      }
    }
    return -1;
  }

  /**
   * Aggregate over every *applicable* case in a group — see {@link #isApplicable}. {@code
   * answerSpanHitRateAt5}/{@code meanSpanRank} are boxed ({@code Double}, not {@code double}) and
   * {@code null} rather than {@code NaN} when not meaningful (no applicable cases at all, or — for
   * {@code meanSpanRank} only — no applicable case's span was found in any returned chunk): plain
   * JSON has no {@code NaN} literal, and the JSON report this feeds ({@link ReportWriter}) would
   * otherwise fail to write for a domain with zero {@code answer_span} cases, i.e. {@code
   * comic-characters} today.
   */
  public record Aggregate(int applicableCases, Double answerSpanHitRateAt5, Double meanSpanRank) {

    public static final Aggregate NOT_APPLICABLE = new Aggregate(0, null, null);
  }

  public static Aggregate aggregate(List<ChunkQueryResult> results) {
    if (results.isEmpty()) {
      return Aggregate.NOT_APPLICABLE;
    }
    int n = results.size();
    double hitRate = results.stream().mapToDouble(ChunkQueryResult::hitRateAt5).sum() / n;
    // Mean rank only over cases the span was actually found in — a case with spanChunkRank=-1
    // contributes to hitRate (as a 0) but has no meaningful rank to average.
    var found = results.stream().filter(r -> r.spanChunkRank() > 0).toList();
    Double meanRank =
        found.isEmpty()
            ? null
            : found.stream().mapToInt(ChunkQueryResult::spanChunkRank).average().orElseThrow();
    return new Aggregate(n, hitRate, meanRank);
  }
}
