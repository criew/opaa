package io.opaa.health;

import io.opaa.compliance.DataFlow;
import io.opaa.llm.EmbeddingModelNotInstalledException;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Embeds one text with the embedding model. Without an installed one the state is {@code UNKNOWN}
 * with {@code embeddingModel: NOT_CONFIGURED}: an installation in progress or one that skipped its
 * embedding model is a regular state, no outage (ADR-0060, Entscheidungen 4 and 6).
 */
@DataFlow("embedding-model")
@Component
@ConditionalOnProperty(name = "management.health.embeddings.enabled", matchIfMissing = true)
public class EmbeddingsHealthIndicator implements HealthIndicator {

  /** The detail value of a role without a model. */
  static final String NOT_CONFIGURED = "NOT_CONFIGURED";

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
      return Health.unknown().withDetail("embeddingModel", NOT_CONFIGURED).build();
    } catch (Exception e) {
      return Health.down(e)
          .withDetail("provider", embeddingModel.getClass().getSimpleName())
          .build();
    }
  }
}
