package io.opaa.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Resolves the currently configured embedding model for the read-only admin block (#759,
 * docs/features/llm-integration.md#sofortige-wirkung). The embedding model is deliberately not
 * exposed as an editable resource: a change requires a full re-index because existing vectors
 * become incomparable, so it stays a configuration value picked at startup, not something the admin
 * API lets anyone edit.
 *
 * <p>Reads the same {@code spring.ai.model.embedding}/{@code spring.ai.ollama.embedding.model}
 * properties that {@code application.yml} already resolves the actual {@code EmbeddingModel} bean
 * from - not the bean itself, since Spring AI's embedding clients do not expose their configured
 * model identifier back out again. Since #773 reverted embedding from Ollama's OpenAI-compatible
 * {@code /v1/embeddings} endpoint back to its native API (a measurable retrieval-quality regression
 * against the eval baseline, see issue #773), {@code spring.ai.model.embedding} is fixed to {@code
 * ollama} unconditionally - unlike chat (still {@code openai}, unaffected), there is deliberately
 * no per-provider switch to bring back here; a future, genuinely configurable embedding model is a
 * later milestone (docs/features/llm-integration.md), not this fix.
 */
@Service
public class EmbeddingInfoService {

  private final String provider;
  private final String model;
  private final int dimensions;

  public EmbeddingInfoService(
      @Value("${spring.ai.model.embedding}") String provider,
      @Value("${spring.ai.ollama.embedding.model}") String model,
      @Value("${spring.ai.vectorstore.pgvector.dimensions}") int dimensions) {
    this.provider = provider;
    this.model = model;
    this.dimensions = dimensions;
  }

  public EmbeddingInfo getEmbeddingInfo() {
    return new EmbeddingInfo(provider, model, dimensions);
  }
}
