package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link EmbeddingInfoService} reads the configured provider, model identifier and dimensions
 * straight through (#759) - proven directly against the constructor rather than a Spring context,
 * since it has no other collaborators. Since #773 there is only one connection path ({@code
 * ollama}) to read the model from - embedding was reverted to Ollama's native API (see issue #773);
 * there is deliberately no per-provider switch to test here.
 */
class EmbeddingInfoServiceTest {

  @Test
  void reportsTheConfiguredProviderModelAndDimensions() {
    EmbeddingInfoService service = new EmbeddingInfoService("ollama", "nomic-embed-text", 768);

    EmbeddingInfo info = service.getEmbeddingInfo();

    assertThat(info.provider()).isEqualTo("ollama");
    assertThat(info.model()).isEqualTo("nomic-embed-text");
    assertThat(info.dimensions()).isEqualTo(768);
  }
}
