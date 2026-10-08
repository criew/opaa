package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VectorStoreStatisticsTest {

  @Test
  void acceptsTheFullWindow() {
    assertThatCode(() -> VectorStoreStatistics.requireFullWindow(190, 190, 998))
        .doesNotThrowAnyException();
  }

  @Test
  void acceptsAStoreSmallerThanTheWindow() {
    assertThatCode(() -> VectorStoreStatistics.requireFullWindow(30, 190, 30))
        .doesNotThrowAnyException();
  }

  @Test
  void namesTheEfSearchCapWhenTheWindowIsShort() {
    assertThatThrownBy(() -> VectorStoreStatistics.requireFullWindow(40, 190, 998))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("returned 40 instead of 190 hits")
        .hasMessageContaining("hnsw.ef_search");
  }
}
