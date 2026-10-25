package io.opaa.llm;

import org.springframework.ai.model.SpringAIModels;
import org.springframework.stereotype.Service;

/**
 * The installed embedding model for the read-only admin block, the instance register and the index
 * reconciliation (docs/features/llm-integration.md#sofortige-wirkung), read from {@code
 * embedding_model}. Deliberately read-only: a change requires a full re-index because existing
 * vectors become incomparable (ADR-0060, Entscheidung 10).
 */
@Service
public class EmbeddingInfoService {

  private final EmbeddingModelService embeddingModels;

  public EmbeddingInfoService(EmbeddingModelService embeddingModels) {
    this.embeddingModels = embeddingModels;
  }

  /** The installed model, or {@link EmbeddingInfo#NOT_INSTALLED}. */
  public EmbeddingInfo getEmbeddingInfo() {
    return embeddingModels
        .installed()
        .map(
            settings ->
                new EmbeddingInfo(
                    SpringAIModels.OPENAI,
                    settings.getModelIdentifier(),
                    settings.getDimensions(),
                    ModelEndpointUri.containsCredentials(settings.getBaseUrl())
                        ? null
                        : settings.getBaseUrl()))
        .orElse(EmbeddingInfo.NOT_INSTALLED);
  }
}
