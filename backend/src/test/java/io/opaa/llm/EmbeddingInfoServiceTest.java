package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link EmbeddingInfoService} reads the configured provider, model identifier and dimensions
 * straight through (#759) - proven directly against the constructor rather than a Spring context,
 * since it has no other collaborators. Since #762 there is only one connection path ({@code
 * openai}) to read the model from - the per-provider switch this test used to exercise is gone
 * along with the removed {@code spring.ai.ollama.embedding.model} property.
 */
class EmbeddingInfoServiceTest {

  @Test
  void reportsTheConfiguredProviderModelAndDimensions() {
    EmbeddingInfoService service = new EmbeddingInfoService("openai", "nomic-embed-text", 768);

    EmbeddingInfo info = service.getEmbeddingInfo();

    assertThat(info.provider()).isEqualTo("openai");
    assertThat(info.model()).isEqualTo("nomic-embed-text");
    assertThat(info.dimensions()).isEqualTo(768);
  }
}
