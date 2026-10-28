package io.opaa.health;

import io.opaa.compliance.DataFlow;
import io.opaa.llm.EmbeddingModelNotInstalledException;
import io.opaa.llm.EmbeddingModelService;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Embeds one text with the embedding model. Without an installed one the state is {@code UNKNOWN}
 * with {@code embeddingModel: NOT_CONFIGURED}: an installation in progress or one that skipped its
 * embedding model is a regular state, no outage (ADR-0060, Entscheidungen 4 and 6). A vector table
 * with chunks but no model row is an error, {@code DOWN} with {@code embeddingModel:
 * MISSING_WITH_CHUNKS}.
 */
@DataFlow("embedding-model")
@Component
@ConditionalOnProperty(name = "management.health.embeddings.enabled", matchIfMissing = true)
public class EmbeddingsHealthIndicator implements HealthIndicator {

  /** The detail value of a role without a model. */
  static final String NOT_CONFIGURED = "NOT_CONFIGURED";

  /** The detail value of chunks in the vector table without a model row. */
  static final String MISSING_WITH_CHUNKS = "MISSING_WITH_CHUNKS";

  private final EmbeddingModel embeddingModel;
  private final EmbeddingModelService embeddingModels;

  public EmbeddingsHealthIndicator(
      EmbeddingModel embeddingModel, EmbeddingModelService embeddingModels) {
    this.embeddingModel = embeddingModel;
    this.embeddingModels = embeddingModels;
  }

  /** {@code UNKNOWN} without a model, {@code DOWN} when chunks are left without one. */
  static Health withoutModel(EmbeddingModelService embeddingModels, Health.Builder health) {
    return embeddingModels.chunksWithoutModel()
        ? health.down().withDetail("embeddingModel", MISSING_WITH_CHUNKS).build()
        : health.unknown().withDetail("embeddingModel", NOT_CONFIGURED).build();
  }

  @Override
  public Health health() {
    try {
      embeddingModel.embed("health check");
      String provider = embeddingModel.getClass().getSimpleName();
      return Health.up().withDetail("provider", provider).build();
    } catch (EmbeddingModelNotInstalledException e) {
      return withoutModel(embeddingModels, Health.unknown());
    } catch (Exception e) {
      return Health.down(e)
          .withDetail("provider", embeddingModel.getClass().getSimpleName())
          .build();
    }
  }
}
