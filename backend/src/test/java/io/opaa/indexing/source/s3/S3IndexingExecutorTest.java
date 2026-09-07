package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.source.IndexingRunFailedException;
import io.opaa.indexing.source.IndexingSourceType;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import org.junit.jupiter.api.Test;

/**
 * The registered S3 executor declares the one mode of ADR-0027 (Entscheidung 3) and, until the full
 * sync exists, ends every run with a German failure instead of an empty success.
 */
class S3IndexingExecutorTest {

  private final S3IndexingExecutor executor = new S3IndexingExecutor(null);

  @Test
  void servesS3WithTheFullModeOnly() {
    assertThat(executor.sourceType()).isEqualTo(IndexingSourceType.S3);
    assertThat(executor.runModes())
        .containsExactly(
            java.util.Map.entry(IndexingRunMode.FULL, VanishedDocumentPolicy.REMOVE_ON_ABSENCE));
    assertThat(executor.defaultRunMode(null)).isEqualTo(IndexingRunMode.FULL);
  }

  @Test
  void everyRunFailsAsNotYetAvailable() {
    assertThatThrownBy(() -> executor.indexScopes(null))
        .isInstanceOf(IndexingRunFailedException.class)
        .hasMessage(S3IndexingExecutor.NOT_YET_AVAILABLE)
        .hasMessageContaining("noch nicht vollständig verfügbar");
  }
}
