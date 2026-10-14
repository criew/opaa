package io.opaa.llm;

import org.springframework.ai.model.SpringAIModels;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingProperties;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Resolves the currently configured embedding model for the read-only admin block
 * (docs/features/llm-integration.md#sofortige-wirkung). The embedding model is deliberately not
 * exposed as an editable resource: a change requires a full re-index because existing vectors
 * become incomparable, so it stays a configuration value picked at startup, not something the admin
 * API lets anyone edit.
 *
 * <p>Reads the bound Spring AI properties the actual {@code EmbeddingModel} bean is built from -
 * not the bean itself, since Spring AI's OpenAI embedding client does not expose its configured
 * model identifier back out again. {@code spring.ai.model.embedding} is fixed to {@code openai}, so
 * there is only ever one connection path to read from.
 */
@Service
public class EmbeddingInfoService {

  private final String provider;
  private final String model;
  private final int dimensions;
  private final String baseUrl;

  @Autowired
  public EmbeddingInfoService(
      OpenAiEmbeddingProperties embedding, PgVectorStoreProperties vectorStore) {
    this(
        SpringAIModels.OPENAI,
        embedding.getModel(),
        vectorStore.getDimensions(),
        embedding.getBaseUrl());
  }

  EmbeddingInfoService(String provider, String model, int dimensions, String baseUrl) {
    this.provider = provider;
    this.model = model;
    this.dimensions = dimensions;
    this.baseUrl = ModelEndpointUri.containsCredentials(baseUrl) ? null : baseUrl;
  }

  public EmbeddingInfo getEmbeddingInfo() {
    return new EmbeddingInfo(provider, model, dimensions, baseUrl);
  }
}
