package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.DocumentSourceType;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The run types and the run-bearing document source types are the same set, by name. */
class IndexingSourceTypeTest {

  @Test
  void everyRunTypeMapsToADocumentSourceTypeWithARunAndBack() {
    for (IndexingSourceType runType : IndexingSourceType.values()) {
      DocumentSourceType sourceType = runType.documentSourceType();
      assertThat(sourceType.hasIndexingRun()).as(runType.name()).isTrue();
      assertThat(IndexingSourceType.of(sourceType)).isEqualTo(runType);
    }
    assertThat(
            Arrays.stream(DocumentSourceType.values())
                .filter(DocumentSourceType::hasIndexingRun)
                .map(IndexingSourceType::of))
        .containsExactlyInAnyOrder(IndexingSourceType.values());
  }

  @Test
  void aSourceTypeWithoutARunHasNoRunType() {
    assertThatThrownBy(() -> IndexingSourceType.of(DocumentSourceType.UPLOAD))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UPLOAD");
  }
}
