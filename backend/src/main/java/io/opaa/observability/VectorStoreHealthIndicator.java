package io.opaa.observability;

import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "management.health.vectorstore.enabled", matchIfMissing = true)
public class VectorStoreHealthIndicator implements HealthIndicator {

  private final VectorStore vectorStore;

  public VectorStoreHealthIndicator(VectorStore vectorStore) {
    this.vectorStore = vectorStore;
  }

  @Override
  public Health health() {
    try {
      vectorStore.similaritySearch(SearchRequest.builder().query("health").topK(1).build());
      String provider = vectorStore.getClass().getSimpleName();
      return Health.up().withDetail("provider", provider).build();
    } catch (Exception e) {
      return Health.down(e).build();
    }
  }
}
