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
 */
@ConfigurationProperties(prefix = "opaa.query")
public record QueryProperties(int topK, double similarityThreshold) {

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
  }
}
