package io.opaa.query;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for the RAG query pipeline.
 *
 * @param topK number of chunks {@link MmrSelector} finally selects for the answer prompt, out of
 *     the {@link #fetchK} candidates {@code similaritySearch} returns (#914). Default 8 (previously
 *     5, raised as part of #914's Maßnahme D): 8 chunks of ~500 tokens each produce ~4 000 tokens
 *     of context - the extra headroom MMR needs to trade in a redundant top-relevance chunk for a
 *     less relevant but topically distinct one without shrinking below the pre-#914 five-chunk
 *     floor for any single-topic question.
 * @param fetchK number of candidates {@code similaritySearch} itself retrieves, before {@link
 *     MmrSelector} narrows them down to {@link #topK} (#914). Default 25: large enough that a
 *     dominant topic's redundant chunks do not crowd out every candidate from a second, less
 *     dominant topic in a multi-topic question (the #912 failure mode), while staying a single
 *     {@code similaritySearch} call - no additional embedding or LLM API call per query.
 * @param mmrLambda the relevance/diversity trade-off {@link MmrSelector} applies when narrowing
 *     {@link #fetchK} candidates down to {@link #topK} (#914): each candidate's selection score is
 *     {@code mmrLambda * relevance - (1 - mmrLambda) * maxSimilarityToAlreadySelected}. Default
 *     0.7: favors relevance while still penalizing near-duplicate chunks. {@code 1.0} disables the
 *     diversity term entirely and reproduces plain top-{@code topK}-by-relevance selection (the
 *     pre-#914 behaviour), an explicitly reachable escape hatch rather than a separate on/off flag.
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
 */
@ConfigurationProperties(prefix = "opaa.query")
public record QueryProperties(
    int topK,
    int fetchK,
    double mmrLambda,
    double similarityThreshold,
    @DefaultValue("1.0") double permissionHistorySampleRate) {

  public QueryProperties {
    if (topK <= 0) {
      topK = 8;
    }
    if (topK > 100) {
      throw new IllegalArgumentException("topK must be at most 100, got " + topK);
    }
    if (fetchK <= 0) {
      fetchK = 25;
    }
    if (fetchK > 200) {
      throw new IllegalArgumentException("fetchK must be at most 200, got " + fetchK);
    }
    if (fetchK < topK) {
      throw new IllegalArgumentException(
          "fetchK must be at least topK, got fetchK=" + fetchK + " topK=" + topK);
    }
    if (mmrLambda <= 0.0) {
      mmrLambda = 0.7;
    }
    if (mmrLambda > 1.0) {
      throw new IllegalArgumentException("mmrLambda must be at most 1.0, got " + mmrLambda);
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
  }
}
