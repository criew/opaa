package io.opaa.query.retrieval.search;

import static io.opaa.query.retrieval.RetrievalPipelineTestSupport.context;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.opaa.query.QueryProperties;
import io.opaa.query.retrieval.RetrievalState;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

/** The vector path's stage on its own (docs/handbuch/suche.md, Stufe 4). */
class VectorSearchStageTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();
  private static final QueryProperties PROPERTIES =
      new QueryProperties(8, 25, 1.0, 0.3, 1.0, false, 3, 2, false, 50);

  private final VectorStore vectorStore = mock(VectorStore.class);

  /**
   * A search stage without the filter stage before it must fail loudly, never search unfiltered.
   */
  @Test
  void searchStageRefusesToRunWithoutAPermissionFilter() {
    assertThatThrownBy(
            () ->
                new VectorSearchStage(vectorStore)
                    .apply(context(Set.of(LIBRARY_ID), PROPERTIES), RetrievalState.initial()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ADR-0008");
    verifyNoInteractions(vectorStore);
  }
}
