package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link EmbeddingInfoService} reports the one provider {@code openai} and reads the configured
 * model identifier and dimensions straight through - proven directly against the constructor rather
 * than a Spring context, since it has no other collaborators.
 */
class EmbeddingInfoServiceTest {

  @Test
  void reportsTheProviderAndTheConfiguredModelAndDimensions() {
    EmbeddingInfoService service =
        new EmbeddingInfoService("nomic-embed-text", 768, "http://host.docker.internal:11434/v1");

    EmbeddingInfo info = service.getEmbeddingInfo();

    assertThat(info.provider()).isEqualTo("openai");
    assertThat(info.model()).isEqualTo("nomic-embed-text");
    assertThat(info.dimensions()).isEqualTo(768);
    assertThat(info.baseUrl()).isEqualTo("http://host.docker.internal:11434/v1");
  }

  @Test
  void withholdsABaseUrlThatCarriesCredentials() {
    EmbeddingInfoService service =
        new EmbeddingInfoService(
            "nomic-embed-text", 768, "https://user:secret@llm.example.invalid/v1");

    assertThat(service.getEmbeddingInfo().baseUrl()).isNull();
  }
}
