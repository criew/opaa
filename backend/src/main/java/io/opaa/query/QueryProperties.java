package io.opaa.query;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for the RAG query pipeline.
 *
 * @param topK number of most relevant document chunks to retrieve from the vector store per query.
 *     Default 5: a RAG best-practice value — 5 chunks of ~500 tokens each produce ~2 500 tokens of
 *     context, balancing relevance against noise and LLM context-window cost.
 * @param similarityThreshold minimum cosine-similarity score a chunk must reach to be included in
 *     results. Default 0.3: empirically tested — lower values surface too much noise, higher values
 *     miss relevant documents on imprecise user queries.
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
    int topK, double similarityThreshold, @DefaultValue("1.0") double permissionHistorySampleRate) {

  public QueryProperties {
    if (topK <= 0) {
      topK = 5;
    }
    if (topK > 100) {
      throw new IllegalArgumentException("topK must be at most 100, got " + topK);
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
