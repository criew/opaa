package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class EmbeddingsHealthIndicatorTest {

  @Test
  void reportsUpWhenEmbeddingSucceeds() {
    EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
    EmbeddingsHealthIndicator indicator = new EmbeddingsHealthIndicator(embeddingModel);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsKey("provider");
  }

  @Test
  void reportsDownWhenEmbeddingFails() {
    EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("Connection refused"));
    EmbeddingsHealthIndicator indicator = new EmbeddingsHealthIndicator(embeddingModel);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsKey("error");
  }
}
