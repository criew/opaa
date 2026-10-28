package io.opaa.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.llm.EmbeddingModelNotInstalledException;
import io.opaa.llm.EmbeddingModelService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class EmbeddingsHealthIndicatorTest {

  private final EmbeddingModelService models = mock(EmbeddingModelService.class);

  @Test
  void reportsUpWhenEmbeddingSucceeds() {
    EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
    EmbeddingsHealthIndicator indicator = new EmbeddingsHealthIndicator(embeddingModel, models);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsKey("provider");
  }

  @Test
  void reportsDownWhenEmbeddingFails() {
    EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("Connection refused"));
    EmbeddingsHealthIndicator indicator = new EmbeddingsHealthIndicator(embeddingModel, models);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsKey("error");
  }

  @Test
  void aMissingModelIsUnknownAndNotConfiguredAlsoAfterTheInstallation() {
    EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    when(embeddingModel.embed(anyString()))
        .thenThrow(mock(EmbeddingModelNotInstalledException.class));
    EmbeddingsHealthIndicator indicator = new EmbeddingsHealthIndicator(embeddingModel, models);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
    assertThat(health.getDetails()).containsEntry("embeddingModel", "NOT_CONFIGURED");
  }

  @Test
  void chunksWithoutAModelRowAreDown() {
    EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    when(embeddingModel.embed(anyString()))
        .thenThrow(mock(EmbeddingModelNotInstalledException.class));
    when(models.chunksWithoutModel()).thenReturn(true);

    Health health = new EmbeddingsHealthIndicator(embeddingModel, models).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("embeddingModel", "MISSING_WITH_CHUNKS");
  }
}
