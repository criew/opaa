package io.opaa.indexing.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfluenceSyncStateTest {

  @Test
  void aFreshStateStartsCleanAndCompletesWithTheAnchorOfTheRun() {
    ConfluenceSyncState state = new ConfluenceSyncState(UUID.randomUUID());
    assertThat(state.isFullSyncInterrupted()).isFalse();
    assertThat(state.completedSpaceKeys()).isEmpty();

    UUID job = UUID.randomUUID();
    state.beginFullSync(job);
    state.markSpaceCompleted("ENG");
    state.markSpaceCompleted("HR");
    assertThat(state.getFullSyncJobId()).isEqualTo(job);
    assertThat(state.isFullSyncInterrupted()).as("in progress counts as interrupted").isTrue();
    assertThat(state.completedSpaceKeys()).containsExactly("ENG", "HR");

    Instant anchor = Instant.parse("2026-09-03T10:00:00Z");
    state.completeFullSync(anchor);
    assertThat(state.isFullSyncInterrupted()).isFalse();
    assertThat(state.getIncrementalAnchor()).isEqualTo(anchor);
    assertThat(state.getFullSyncCompletedAt()).isNotNull();
    assertThat(state.getFullSyncJobId()).isNull();
    assertThat(state.completedSpaceKeys()).isEmpty();
  }

  @Test
  void aNewRunAfterAnInterruptionKeepsTheCompletedSpacesButAfterACompletionStartsOver() {
    ConfluenceSyncState state = new ConfluenceSyncState(UUID.randomUUID());
    state.beginFullSync(UUID.randomUUID());
    state.markSpaceCompleted("ENG");

    state.beginFullSync(UUID.randomUUID());
    assertThat(state.completedSpaceKeys()).as("resumed").containsExactly("ENG");

    state.markSpaceCompleted("HR");
    state.completeFullSync(Instant.now());
    state.beginFullSync(UUID.randomUUID());
    assertThat(state.completedSpaceKeys()).as("clean after a completed sync").isEmpty();
  }
}
