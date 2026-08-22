package io.opaa.llm;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Resolves the currently configured embedding model for the read-only admin block (#759,
 * docs/features/llm-integration.md#sofortige-wirkung). The embedding model is deliberately not
 * exposed as an editable resource: a change requires a full re-index because existing vectors
 * become incomparable, so it stays a configuration value picked at startup, not something the admin
 * API lets anyone edit.
 *
 * <p>Reads the same {@code spring.ai.model.embedding} switch and per-provider {@code model}
 * properties that {@code application.yml} already resolves the actual {@code EmbeddingModel} bean
 * from - not the bean itself, since neither Spring AI's OpenAI nor Ollama embedding client exposes
 * its configured model identifier back out again.
 */
@Service
public class EmbeddingInfoService {

  private final String provider;
  private final String openAiModel;
  private final String ollamaModel;
  private final int dimensions;

  public EmbeddingInfoService(
      @Value("${spring.ai.model.embedding}") String provider,
      @Value("${spring.ai.openai.embedding.model}") String openAiModel,
      @Value("${spring.ai.ollama.embedding.model}") String ollamaModel,
      @Value("${spring.ai.vectorstore.pgvector.dimensions}") int dimensions) {
    this.provider = provider;
    this.openAiModel = openAiModel;
    this.ollamaModel = ollamaModel;
    this.dimensions = dimensions;
  }

  /**
   * Explicit switch rather than "anything not openai is ollama" (#759 review): {@code
   * spring.ai.model.embedding} only ever has two supported values in {@code application.yml}, but a
   * typo or an unsupported third value must not silently report the Ollama model as if it were
   * running - that would show an admin a model the deployment never actually configured.
   */
  public EmbeddingInfo getEmbeddingInfo() {
    String model =
        switch (provider.toLowerCase(Locale.ROOT)) {
          case "openai" -> openAiModel;
          case "ollama" -> ollamaModel;
          default -> "unbekannt (" + provider + ")";
        };
    return new EmbeddingInfo(provider, model, dimensions);
  }
}
