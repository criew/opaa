package io.opaa.query;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
 *     {@code [0.0, 1.0]} (#889, O1). Default 0.01 (1 %): the check reconstructs the historized
 *     permission set from three append-only-growing tables on every call it runs for, purely to log
 *     a drift warning
 *     (docs/features/security-and-compliance.md#nachweisbarkeit-historisierung-von-rechten) -
 *     running it on every single query paid that cost on the hot path for a signal that is, by
 *     construction, either "no drift" (the overwhelming majority of calls) or a bug that, once
 *     introduced, keeps reproducing on every subsequent query until fixed - a 1 % sample still
 *     surfaces it well within the time it takes to notice, without paying the reconstruction cost
 *     on every request. {@code 1.0} restores the pre-#889 "every query" behaviour for a test or a
 *     deployment that wants it.
 */
@ConfigurationProperties(prefix = "opaa.query")
public record QueryProperties(
    int topK, double similarityThreshold, double permissionHistorySampleRate) {

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
