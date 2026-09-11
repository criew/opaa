package io.opaa.indexing.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link FullTextIndexFillState#isUpToDate()} over its two conditions: no row off the current
 * version, and a row for every chunk. The second is the smoke detector - no write path can produce
 * a chunk without its full-text row, so a shortfall must not read as "up to date".
 */
class FullTextIndexFillStateTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();

  private static FullTextIndexFillState fillState(long total, long indexed, long outdated) {
    return new FullTextIndexFillState(LIBRARY_ID, total, indexed, outdated);
  }

  @Test
  void upToDateWhenEveryChunkCarriesARowAtTheCurrentVersion() {
    assertThat(fillState(10, 10, 0).isUpToDate()).isTrue();
  }

  @Test
  void notUpToDateWithAVersionBacklog() {
    assertThat(fillState(10, 9, 1).isUpToDate()).isFalse();
  }

  @Test
  void notUpToDateWhenARowIsMissingAltogetherEvenWithoutABacklog() {
    assertThat(fillState(10, 9, 0).isUpToDate()).isFalse();
  }

  @Test
  void aLibraryWithoutChunksIsUpToDate() {
    assertThat(fillState(0, 0, 0).isUpToDate()).isTrue();
  }
}
