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
 * <p>Reads the same {@code spring.ai.model.embedding}/{@code spring.ai.openai.embedding.model}
 * properties that {@code application.yml} already resolves the actual {@code EmbeddingModel} bean
 * from - not the bean itself, since Spring AI's OpenAI embedding client does not expose its
 * configured model identifier back out again. Since #762 removed the native Ollama starter and
 * fixed {@code spring.ai.model.embedding} to {@code openai} unconditionally, there is only ever one
 * connection path to read from - the per-provider switch this class carried before #762 (choosing
 * between an {@code openai} and an {@code ollama} model property) is gone along with the second
 * property it used to read.
 */
@Service
public class EmbeddingInfoService {

  private final String provider;
  private final String model;
  private final int dimensions;

  public EmbeddingInfoService(
      @Value("${spring.ai.model.embedding}") String provider,
      @Value("${spring.ai.openai.embedding.model}") String model,
      @Value("${spring.ai.vectorstore.pgvector.dimensions}") int dimensions) {
    this.provider = provider;
    this.model = model;
    this.dimensions = dimensions;
  }

  public EmbeddingInfo getEmbeddingInfo() {
    return new EmbeddingInfo(provider, model, dimensions);
  }
}
