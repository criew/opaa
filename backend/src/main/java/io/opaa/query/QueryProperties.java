package io.opaa.query;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for the RAG query pipeline.
 *
 * @param topK number of chunks {@link MmrSelector} finally selects for the answer prompt, out of
 *     the {@link #fetchK} candidates {@code similaritySearch} returns (#914). Default 8 (previously
 *     5, raised as part of #914's Maßnahme D): 8 chunks of ~1 000 tokens each (see {@code
 *     opaa.indexing.chunk-size}) produce ~8 000 tokens of context - the extra headroom MMR needs to
 *     trade in a redundant top-relevance chunk for a less relevant but topically distinct one
 *     without shrinking below the pre-#914 five-chunk floor for any single-topic question.
 * @param fetchK number of candidates {@code similaritySearch} itself retrieves, before {@link
 *     MmrSelector} narrows them down to {@link #topK} (#914). Default 25: large enough that a
 *     dominant topic's redundant chunks do not crowd out every candidate from a second, less
 *     dominant topic in a multi-topic question (the #912 failure mode), while staying a single
 *     {@code similaritySearch} call - no additional embedding or LLM API call per query. A missing
 *     configuration value (see the compact constructor) normalizes to {@code max(25, topK)}, not a
 *     flat 25, so a deployment that already configured {@code topK} above 25 does not fail {@link
 *     #fetchK} {@code < topK}'s validation below on a property it never touched. See
 *     docs/deployment.md for the operator-facing version of this note.
 * @param mmrLambda the relevance/diversity trade-off {@link MmrSelector} applies when narrowing
 *     {@link #fetchK} candidates down to {@link #topK} (#914): each candidate's selection score is
 *     {@code mmrLambda * relevance - (1 - mmrLambda) * maxSimilarityToAlreadySelected}, where the
 *     similarity term is cosine similarity of the real chunk embeddings (see {@link
 *     ChunkEmbeddingLookup}, {@link MmrSelector}). Default <b>{@code 1.0}</b> - diversity selection
 *     is implemented but ships disabled, an explicit opt-in via a lower value, not a separate
 *     on/off flag (plain top-{@code topK}-by-relevance selection, the pre-#914 behaviour, whenever
 *     {@code mmrLambda == 1.0}; {@code QueryService#query} also then skips the {@link
 *     ChunkEmbeddingLookup} round trip entirely, since it could not affect the result). Backed by
 *     {@code @DefaultValue} (not the manual-default pattern {@link #topK}/{@link #fetchK} use
 *     below) so an explicitly configured {@code 0.0} is honored rather than silently raised -
 *     {@code 0.0} is a legal (if extreme) point on the range, not an "unset" sentinel the way a
 *     non-positive {@code topK}/{@code fetchK} is. The measured numbers behind {@code 1.0}'s choice
 *     over a lower value live in docs/deployment.md and docs/features/data-indexing-rag.md, not
 *     here.
 * @param similarityThreshold minimum cosine-similarity score a chunk must reach to be included in
 *     results. Default 0.3: empirically tested — lower values surface too much noise, higher values
 *     miss relevant documents on imprecise user queries. Applied inside {@code similaritySearch}
 *     itself (#914): a chunk below the threshold never becomes an MMR candidate, so diversity can
 *     never pull a below-threshold chunk into the final selection.
 * @param permissionHistorySampleRate the fraction of queries {@link
 *     QueryService#checkAgainstPermissionHistory} actually runs for, expressed as a probability in
 *     {@code [0.0, 1.0]} (#889, O1) - see
 *     docs/features/security-and-compliance.md#nachweisbarkeit-historisierung-von-rechten. Default
 *     {@code 1.0}: the pre-#889 behaviour, every query checked, unchanged without an explicit
 *     maintainer decision to lower it - the check is a compliance control, and a missing property
 *     must not silently disable most of it. {@code @DefaultValue} backs this at the binder level
 *     too, not just in {@code application.yml}, so a caller assembling an {@code Environment}
 *     without that file still gets {@code 1.0}, never Java's primitive-{@code double} zero.
 *     Lowering it trades that guarantee for less load from the check's three additional queries
 *     against ever-growing tables per sampled query - an operator's explicit choice, not this
 *     project's default.
 * @param queryDecompositionEnabled whether {@code QueryService#query} asks {@code
 *     QueryDecompositionService} to split the question into up to {@link #maxSubQueries}
 *     independent search queries before retrieval (#923) - each becomes its own permission- and
 *     threshold-scoped {@code similaritySearch} call, fused by {@code ReciprocalRankFusion}.
 *     Default {@code true}: on LLM failure or unparsable output {@code
 *     QueryDecompositionService#decompose} returns an empty list and {@code QueryService#query}
 *     falls back to today's single-query retrieval unchanged, so leaving this on costs at most one
 *     extra LLM round trip per query, never a broken query. Set to {@code false} to skip that round
 *     trip entirely (e.g. no query-decomposition-capable model configured).
 * @param maxSubQueries the upper bound on how many independent search queries {@code
 *     QueryDecompositionService#decompose} may return (#923). Default 3: beyond that, {@code
 *     QueryDecompositionService} truncates rather than growing the number of {@code
 *     similaritySearch} calls (and thus retrieval latency) without bound for an adversarial or
 *     confused decomposition response. Each sub-query is independently narrowed to the full {@link
 *     #topK} (see {@code QueryService#retrieveRelevantChunks}), so the overall chunk count stays
 *     capped at {@link #topK} regardless of {@code maxSubQueries}.
 */
@ConfigurationProperties(prefix = "opaa.query")
public record QueryProperties(
    int topK,
    int fetchK,
    @DefaultValue("1.0") double mmrLambda,
    double similarityThreshold,
    @DefaultValue("1.0") double permissionHistorySampleRate,
    @DefaultValue("true") boolean queryDecompositionEnabled,
    @DefaultValue("3") int maxSubQueries) {

  public QueryProperties {
    if (topK <= 0) {
      topK = 8;
    }
    if (topK > 100) {
      throw new IllegalArgumentException("topK must be at most 100, got " + topK);
    }
    if (fetchK <= 0) {
      // See #fetchK's Javadoc: max(25, topK), not a flat 25.
      fetchK = Math.max(25, topK);
    }
    if (fetchK > 200) {
      throw new IllegalArgumentException("fetchK must be at most 200, got " + fetchK);
    }
    if (fetchK < topK) {
      throw new IllegalArgumentException(
          "fetchK must be at least topK, got fetchK=" + fetchK + " topK=" + topK);
    }
    if (mmrLambda < 0.0 || mmrLambda > 1.0) {
      throw new IllegalArgumentException("mmrLambda must be between 0.0 and 1.0, got " + mmrLambda);
    }
    if (similarityThreshold < 0.0 || similarityThreshold > 1.0) {
      throw new IllegalArgumentException(
          "similarityThreshold must be between 0.0 and 1.0, got " + similarityThreshold);
    }
    if (permissionHistorySampleRate < 0.0 || permissionHistorySampleRate > 1.0) {
      throw new IllegalArgumentException(
          "permissionHistorySampleRate must be between 0.0 and 1.0, got "
              + permissionHistorySampleRate);
    }
    if (maxSubQueries <= 0 || maxSubQueries > 10) {
      throw new IllegalArgumentException(
          "maxSubQueries must be between 1 and 10, got " + maxSubQueries);
    }
  }
}
