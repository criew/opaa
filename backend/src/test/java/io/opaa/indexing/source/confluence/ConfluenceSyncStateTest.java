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
  void aFullSyncIsDueWithoutACompletedOneAfterAnInterruptionAndOnceTheIntervalPassed() {
    Instant now = Instant.parse("2026-09-10T10:00:00Z");
    java.time.Duration weekly = java.time.Duration.ofDays(7);
    ConfluenceSyncState state = new ConfluenceSyncState(UUID.randomUUID());
    assertThat(state.isFullSyncDue(weekly, now)).as("never completed").isTrue();

    state.beginFullSync(UUID.randomUUID());
    assertThat(state.isFullSyncDue(weekly, now)).as("in progress / interrupted").isTrue();

    state.completeFullSync(now.minus(java.time.Duration.ofDays(1)));
    assertThat(state.isFullSyncDue(weekly, now.plusSeconds(60))).as("completed just now").isFalse();
    assertThat(state.isFullSyncDue(java.time.Duration.ofSeconds(1), now.plusSeconds(60)))
        .as("interval passed")
        .isTrue();

    state.advanceIncrementalAnchor(now);
    assertThat(state.getIncrementalAnchor()).isEqualTo(now);
    assertThat(state.getFullSyncCompletedAt())
        .as("the anchor does not restart the interval")
        .isBeforeOrEqualTo(Instant.now());
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
