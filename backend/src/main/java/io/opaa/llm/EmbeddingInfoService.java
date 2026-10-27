package io.opaa.llm;

import org.springframework.ai.model.SpringAIModels;
import org.springframework.stereotype.Service;

/**
 * The installed embedding model for the admin page, the instance register and the index
 * reconciliation, read from {@code embedding_model}. Read-only: a change goes through {@link
 * EmbeddingModelAdministration} and a re-embedding, since existing vectors become incomparable
 * (ADR-0061).
 */
@Service
public class EmbeddingInfoService {

  private final EmbeddingModelService embeddingModels;

  public EmbeddingInfoService(EmbeddingModelService embeddingModels) {
    this.embeddingModels = embeddingModels;
  }

  /**
   * The installed model, or {@link EmbeddingInfo#NOT_INSTALLED}. The address leaves without query
   * and fragment, which may carry a key; an address with credentials does not leave at all.
   */
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
                        : ModelEndpointUri.withoutSecrets(settings.getBaseUrl()),
                    settings.getGeneration()))
        .orElse(EmbeddingInfo.NOT_INSTALLED);
  }
}
