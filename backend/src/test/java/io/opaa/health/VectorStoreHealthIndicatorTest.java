package io.opaa.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.indexing.chunk.EmbeddingIdentity;
import io.opaa.indexing.chunk.EmbeddingIndexState;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class VectorStoreHealthIndicatorTest {

  @Test
  void reportsUpWhenVectorStoreIsAccessible() {
    VectorStore vectorStore = mock(VectorStore.class);
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    VectorStoreHealthIndicator indicator =
        new VectorStoreHealthIndicator(vectorStore, new EmbeddingIndexState());

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
        new VectorStoreHealthIndicator(vectorStore, new EmbeddingIndexState());

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsKey("error");
  }

  @Test
  void reportsTheVectorSearchSuspendedWithoutProbingWhileTheIndexIsStale() {
    VectorStore vectorStore = mock(VectorStore.class);
    EmbeddingIndexState state = new EmbeddingIndexState();
    EmbeddingIdentity built = new EmbeddingIdentity("nomic-embed-text", null, 768);
    EmbeddingIdentity configured = new EmbeddingIdentity("bge-m3", null, 1024);
    state.replace(new EmbeddingIndexState.Snapshot(built, configured, configured));
    VectorStoreHealthIndicator indicator = new VectorStoreHealthIndicator(vectorStore, state);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("vectorSearch", "SUSPENDED");
    verifyNoInteractions(vectorStore);
  }
}
