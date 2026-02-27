package io.opaa.indexing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opaa.indexing")
public record IndexingProperties(
    String documentPath, int chunkSize, int batchSize, int retryAttempts, ThreadPool threadPool) {

  public IndexingProperties {
    if (documentPath == null) {
      documentPath = "./documents";
    }
    if (chunkSize <= 0) {
      chunkSize = 1000;
    }
    if (batchSize <= 0) {
      batchSize = 50;
    }
    if (retryAttempts < 0) {
      retryAttempts = 3;
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
