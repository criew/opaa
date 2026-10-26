package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link EmbeddingInfoService} reports the one provider {@code openai} and the installed model
 * identifier, vector size and address, and {@link EmbeddingInfo#NOT_INSTALLED} before an embedding
 * model is installed.
 */
class EmbeddingInfoServiceTest {

  private final EmbeddingModelService models = mock(EmbeddingModelService.class);
  private final EmbeddingInfoService service = new EmbeddingInfoService(models);

  @Test
  void reportsTheProviderAndTheInstalledModelAndDimensions() {
    when(models.installed())
        .thenReturn(Optional.of(installed("http://host.docker.internal:11434/v1")));

    EmbeddingInfo info = service.getEmbeddingInfo();

    assertThat(info.installed()).isTrue();
    assertThat(info.provider()).isEqualTo("openai");
    assertThat(info.model()).isEqualTo("nomic-embed-text");
    assertThat(info.dimensions()).isEqualTo(768);
    assertThat(info.baseUrl()).isEqualTo("http://host.docker.internal:11434/v1");
  }

  @Test
  void withholdsABaseUrlThatCarriesCredentials() {
    when(models.installed())
        .thenReturn(Optional.of(installed("https://user:secret@llm.example.invalid/v1")));

    assertThat(service.getEmbeddingInfo().baseUrl()).isNull();
  }

  @Test
  void reportsNoModelBeforeOneIsInstalled() {
    when(models.installed()).thenReturn(Optional.empty());

    EmbeddingInfo info = service.getEmbeddingInfo();

    assertThat(info.installed()).isFalse();
    assertThat(info.model()).isNull();
    assertThat(info.dimensions()).isZero();
  }

  private static EmbeddingModelSettings installed(String baseUrl) {
    return new EmbeddingModelSettings(
        baseUrl, "nomic-embed-text", null, 2048, 768, Instant.EPOCH, 1, null);
  }
}
