package io.opaa.health;

import io.opaa.compliance.DataFlow;
import io.opaa.llm.EmbeddingModelNotInstalledException;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Embeds one text with the embedding model. Before one is installed the state is {@code UNKNOWN}:
 * an installation in progress is no outage (ADR-0060, Entscheidung 4).
 */
@DataFlow("embedding-model")
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
    } catch (EmbeddingModelNotInstalledException e) {
      return Health.unknown().withDetail("embeddingModel", "NOT_INSTALLED").build();
    } catch (Exception e) {
      return Health.down(e)
          .withDetail("provider", embeddingModel.getClass().getSimpleName())
          .build();
    }
  }
}
