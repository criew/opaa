package io.opaa.observability;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "management.health.embeddings.enabled", matchIfMissing = true)
public class EmbeddingsHealthIndicator implements HealthIndicator {

  private final EmbeddingModel embeddingModel;

  public EmbeddingsHealthIndicator(EmbeddingModel embeddingModel) {
    this.embeddingModel = embeddingModel;
  }

  @Override
  public Health health() {
    try {
      embeddingModel.embed("health check");
      String provider = embeddingModel.getClass().getSimpleName();
      return Health.up().withDetail("provider", provider).build();
    } catch (Exception e) {
      return Health.down(e)
          .withDetail("provider", embeddingModel.getClass().getSimpleName())
          .build();
    }
  }
}
