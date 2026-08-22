package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link EmbeddingInfoService} picks the model identifier matching the configured provider (#759) -
 * proven directly against the constructor rather than a Spring context, since it is a plain
 * three-argument switch with no other collaborators.
 */
class EmbeddingInfoServiceTest {

  @Test
  void reportsTheOllamaModelWhenOllamaIsConfigured() {
    EmbeddingInfoService service =
        new EmbeddingInfoService("ollama", "text-embedding-3-small", "nomic-embed-text", 1536);

    EmbeddingInfo info = service.getEmbeddingInfo();

    assertThat(info.provider()).isEqualTo("ollama");
    assertThat(info.model()).isEqualTo("nomic-embed-text");
    assertThat(info.dimensions()).isEqualTo(1536);
  }

  @Test
  void reportsTheOpenAiModelWhenOpenAiIsConfigured() {
    EmbeddingInfoService service =
        new EmbeddingInfoService("openai", "text-embedding-3-small", "nomic-embed-text", 1536);

    EmbeddingInfo info = service.getEmbeddingInfo();

    assertThat(info.provider()).isEqualTo("openai");
    assertThat(info.model()).isEqualTo("text-embedding-3-small");
  }

  /**
   * #759 review: an unsupported value must not fall back to reporting the Ollama model as if it
   * were configured - that would show an admin a model the deployment never actually set up.
   */
  @Test
  void reportsAnUnknownModelForAnUnsupportedProvider() {
    EmbeddingInfoService service =
        new EmbeddingInfoService("none", "text-embedding-3-small", "nomic-embed-text", 1536);

    EmbeddingInfo info = service.getEmbeddingInfo();

    assertThat(info.provider()).isEqualTo("none");
    assertThat(info.model()).contains("unbekannt");
  }
}
