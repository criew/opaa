package io.opaa.health;

import io.opaa.compliance.DataFlow;
import io.opaa.indexing.chunk.EmbeddingIndexState;
import io.opaa.llm.EmbeddingInfoService;
import io.opaa.llm.EmbeddingModelService;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Probes the vector store with one search. While the index is stale the probe is skipped and the
 * store reported up with the vector search {@code SUSPENDED}: a query vector of the new model does
 * not fit the old column, and a planned re-embedding is no outage. Before an embedding model is
 * installed there is no vector table yet: {@code UNKNOWN} while the installation is pending, {@code
 * DOWN} once it is completed.
 */
@DataFlow("embedding-model")
@Component
@ConditionalOnProperty(name = "management.health.vectorstore.enabled", matchIfMissing = true)
public class VectorStoreHealthIndicator implements HealthIndicator {

  private final VectorStore vectorStore;
  private final EmbeddingIndexState indexState;
  private final EmbeddingInfoService embeddingInfo;
  private final EmbeddingModelService embeddingModels;

  public VectorStoreHealthIndicator(
      VectorStore vectorStore,
      EmbeddingIndexState indexState,
      EmbeddingInfoService embeddingInfo,
      EmbeddingModelService embeddingModels) {
    this.vectorStore = vectorStore;
    this.indexState = indexState;
    this.embeddingInfo = embeddingInfo;
    this.embeddingModels = embeddingModels;
  }

  @Override
  public Health health() {
    String provider = vectorStore.getClass().getSimpleName();
    if (!embeddingInfo.getEmbeddingInfo().installed()) {
      Health.Builder health =
          embeddingModels.missingAfterInstallation() ? Health.down() : Health.unknown();
      return health
          .withDetail("provider", provider)
          .withDetail("embeddingModel", "NOT_INSTALLED")
          .build();
    }
    if (!indexState.isVectorSearchAvailable()) {
      return Health.up()
          .withDetail("provider", provider)
          .withDetail("vectorSearch", "SUSPENDED")
          .build();
    }
    try {
      vectorStore.similaritySearch(SearchRequest.builder().query("health").topK(1).build());
      return Health.up().withDetail("provider", provider).build();
    } catch (Exception e) {
      return Health.down(e).build();
    }
  }
}
