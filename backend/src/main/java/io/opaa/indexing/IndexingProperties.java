package io.opaa.indexing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opaa.indexing")
public record IndexingProperties(
    String documentPath, int chunkSize, int batchSize, int retryAttempts) {

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
  }
}
