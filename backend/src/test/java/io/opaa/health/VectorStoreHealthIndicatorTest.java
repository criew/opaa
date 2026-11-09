package io.opaa.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.indexing.chunk.EmbeddingIdentity;
import io.opaa.indexing.chunk.EmbeddingIndexState;
import io.opaa.llm.EmbeddingInfo;
import io.opaa.llm.EmbeddingInfoService;
import io.opaa.llm.EmbeddingModelService;
import io.opaa.test.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class VectorStoreHealthIndicatorTest {

  private final EmbeddingModelService models = mock(EmbeddingModelService.class);

  private final EmbeddingInfoService embeddingInfo = installed();

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-09T08:00:00Z"));

  @Test
  void reportsUpWhenVectorStoreIsAccessible() {
    VectorStore vectorStore = mock(VectorStore.class);
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    VectorStoreHealthIndicator indicator =
        new VectorStoreHealthIndicator(
            vectorStore, new EmbeddingIndexState(), embeddingInfo, models, clock);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsKey("provider");
  }

  @Test
  void reportsDownWhenVectorStoreIsUnavailable() {
    VectorStore vectorStore = mock(VectorStore.class);
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenThrow(new RuntimeException("Connection refused"));
    VectorStoreHealthIndicator indicator =
        new VectorStoreHealthIndicator(
            vectorStore, new EmbeddingIndexState(), embeddingInfo, models, clock);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsKey("error");
  }

  @Test
  void reportsTheVectorSearchSuspendedWithoutProbingWhileTheIndexIsStale() {
    VectorStore vectorStore = mock(VectorStore.class);
    EmbeddingIndexState state = new EmbeddingIndexState();
    EmbeddingIdentity built = new EmbeddingIdentity("nomic-embed-text", null, 768, 1);
    EmbeddingIdentity configured = new EmbeddingIdentity("bge-m3", null, 1024, 1);
    state.replace(new EmbeddingIndexState.Snapshot(built, configured, configured));
    VectorStoreHealthIndicator indicator =
        new VectorStoreHealthIndicator(vectorStore, state, embeddingInfo, models, clock);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("vectorSearch", "SUSPENDED");
    verifyNoInteractions(vectorStore);
  }

  @Test
  void reportsUnknownWithoutProbingBeforeAnEmbeddingModelIsInstalled() {
    VectorStore vectorStore = mock(VectorStore.class);
    EmbeddingInfoService notInstalled = mock(EmbeddingInfoService.class);
    when(notInstalled.getEmbeddingInfo()).thenReturn(EmbeddingInfo.NOT_INSTALLED);
    VectorStoreHealthIndicator indicator =
        new VectorStoreHealthIndicator(
            vectorStore, new EmbeddingIndexState(), notInstalled, models, clock);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
    assertThat(health.getDetails()).containsEntry("embeddingModel", "NOT_CONFIGURED");
    verifyNoInteractions(vectorStore);
  }

  @Test
  void reportsUnknownAlsoWhenTheCompletedInstallationHasNoEmbeddingModel() {
    EmbeddingInfoService notInstalled = mock(EmbeddingInfoService.class);
    when(notInstalled.getEmbeddingInfo()).thenReturn(EmbeddingInfo.NOT_INSTALLED);

    assertThat(
            new VectorStoreHealthIndicator(
                    mock(VectorStore.class), new EmbeddingIndexState(), notInstalled, models, clock)
                .health()
                .getStatus())
        .isEqualTo(Status.UNKNOWN);
  }

  @Test
  void aSearchWithinTheCacheTimeIsAnsweredFromTheLastProbe() {
    VectorStore vectorStore = mock(VectorStore.class);
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    VectorStoreHealthIndicator indicator =
        new VectorStoreHealthIndicator(
            vectorStore, new EmbeddingIndexState(), embeddingInfo, models, clock);

    indicator.health();
    clock.advance(CachedProbe.TIME_TO_LIVE.minusSeconds(1));
    Health second = indicator.health();

    assertThat(second.getStatus()).isEqualTo(Status.UP);
    verify(vectorStore, times(1)).similaritySearch(any(SearchRequest.class));

    clock.advance(Duration.ofSeconds(1));
    indicator.health();

    verify(vectorStore, times(2)).similaritySearch(any(SearchRequest.class));
  }

  private static EmbeddingInfoService installed() {
    EmbeddingInfoService service = mock(EmbeddingInfoService.class);
    when(service.getEmbeddingInfo()).thenReturn(new EmbeddingInfo("openai", "bge-m3", 1024, null));
    return service;
  }
}
