package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.MetadataBackfillResponse;
import io.opaa.indexing.metadata.MetadataBackfillResult;
import org.junit.jupiter.api.Test;

/**
 * Pins that every counter of a backfill batch reaches the response, and how {@code done} is
 * derived.
 */
class MetadataBackfillResponseMapperTest {

  @Test
  void batchResponseCarriesEveryCounterAndIsNotDoneWhileSomethingAdvanced() {
    MetadataBackfillResponse response =
        MetadataBackfillResponseMapper.toBackfillResponse(new MetadataBackfillResult(4, 1, 2));

    assertThat(response.getProcessedDocuments()).isEqualTo(4);
    assertThat(response.getMarkedForNextRun()).isEqualTo(1);
    assertThat(response.getSkippedDocuments()).isEqualTo(2);
    assertThat(response.getDone()).isFalse();
  }

  @Test
  void aCallThatOnlySkippedIsDone() {
    // Repeating it would retry the same unreachable documents forever - the skip count stays
    // visible in the status display instead.
    MetadataBackfillResponse response =
        MetadataBackfillResponseMapper.toBackfillResponse(new MetadataBackfillResult(0, 0, 3));

    assertThat(response.getSkippedDocuments()).isEqualTo(3);
    assertThat(response.getDone()).isTrue();
  }
}
