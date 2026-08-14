package io.opaa.indexing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the document indexing pipeline.
 *
 * @param documentPath filesystem path where source documents are stored
 * @param chunkSize target token count per chunk. Default 1000: standard for token-based chunking —
 *     balances sufficient context per chunk against retrieval granularity. Valid range: 1–10 000.
 * @param chunkOverlap number of tokens each chunk repeats from the end of its predecessor, so that
 *     a statement straddling a chunk boundary survives in at least one chunk as a whole (issue
 *     #374). Must be smaller than {@code chunkSize}; 0 disables overlap. A negative value is
 *     normalised to 0.
 * @param batchSize number of chunks sent to the embedding model in one call. Default 50: moderate
 *     batch size that avoids memory spikes during embedding generation. Valid range: 1–1 000.
 * @param retryAttempts number of retry attempts for transient failures. Default 3: standard retry
 *     count used with exponential backoff. Valid range: 0–10.
 * @param threadPool thread pool settings for async indexing. Defaults (core=2, max=4, queue=20) are
 *     conservative values suitable for typical single-server deployments.
 */
@ConfigurationProperties(prefix = "opaa.indexing")
public record IndexingProperties(
    String documentPath,
    int chunkSize,
    int chunkOverlap,
    int batchSize,
    int retryAttempts,
    ThreadPool threadPool) {

  public IndexingProperties {
    if (documentPath == null) {
      documentPath = "./documents";
    }
    if (chunkSize <= 0) {
      chunkSize = 1000;
    }
    if (chunkSize > 10000) {
      throw new IllegalArgumentException("chunkSize must be at most 10000, got " + chunkSize);
    }
    // 0 is a meaningful value here (no overlap), so it is not replaced by a default the way
    // chunkSize/batchSize are. Only a nonsensical negative value is normalised.
    if (chunkOverlap < 0) {
      chunkOverlap = 0;
    }
    if (chunkOverlap >= chunkSize) {
      throw new IllegalArgumentException(
          "chunkOverlap must be smaller than chunkSize ("
              + chunkSize
              + "), got "
              + chunkOverlap
              + "; an overlap of at least the chunk size would never advance through the text");
    }
    if (batchSize <= 0) {
      batchSize = 50;
    }
    if (batchSize > 1000) {
      throw new IllegalArgumentException("batchSize must be at most 1000, got " + batchSize);
    }
    if (retryAttempts < 0) {
      retryAttempts = 3;
    }
    if (retryAttempts > 10) {
      throw new IllegalArgumentException("retryAttempts must be at most 10, got " + retryAttempts);
    }
    if (threadPool == null) {
      threadPool = new ThreadPool(2, 4, 20);
    }
  }

  public record ThreadPool(int coreSize, int maxSize, int queueCapacity) {

    public ThreadPool {
      if (coreSize <= 0) {
        coreSize = 2;
      }
      if (maxSize <= 0) {
        maxSize = 4;
      }
      if (queueCapacity < 0) {
        queueCapacity = 20;
      }
    }
  }
}
