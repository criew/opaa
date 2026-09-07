package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link SourceSyncState}: resumes the scopes of an interrupted full sync, starts clean after a
 * completed one, and carries the incremental anchor only for a connector that sets one.
 */
class SourceSyncStateTest {

  @Test
  void aFreshStateStartsCleanAndCompletesWithTheAnchorOfTheRun() {
    SourceSyncState state = new SourceSyncState(UUID.randomUUID());
    assertThat(state.isFullSyncInterrupted()).isFalse();
    assertThat(state.completedScopeKeys()).isEmpty();

    UUID job = UUID.randomUUID();
    state.beginFullSync(job);
    state.markScopeCompleted("ENG");
    state.markScopeCompleted("HR");
    assertThat(state.getFullSyncJobId()).isEqualTo(job);
    assertThat(state.isFullSyncInterrupted()).as("in progress counts as interrupted").isTrue();
    assertThat(state.completedScopeKeys()).containsExactly("ENG", "HR");

    Instant anchor = Instant.parse("2026-09-03T10:00:00Z");
    state.completeFullSync(anchor, anchor);
    assertThat(state.isFullSyncInterrupted()).isFalse();
    assertThat(state.getIncrementalAnchor()).isEqualTo(anchor);
    assertThat(state.getFullSyncCompletedAt()).isNotNull();
    assertThat(state.getFullSyncJobId()).isNull();
    assertThat(state.completedScopeKeys()).isEmpty();
  }

  @Test
  void aConnectorWithoutAnIncrementalModeCompletesWithoutAnAnchorAndStaysDueForAFullSync() {
    SourceSyncState state = new SourceSyncState(UUID.randomUUID());
    state.beginFullSync(UUID.randomUUID());
    state.markScopeCompleted("dokumente/2025/");
    state.markScopeCompleted("satzungen");
    state.markScopeCompleted("dokumente/2025/");
    assertThat(state.completedScopeKeys())
        .as("a scope is completed once, in first-completion order")
        .containsExactly("dokumente/2025/", "satzungen");

    Instant done = Instant.parse("2026-09-06T20:00:00Z");
    state.completeFullSync(done);
    assertThat(state.isFullSyncInterrupted()).isFalse();
    assertThat(state.getFullSyncCompletedAt()).isEqualTo(done);
    assertThat(state.getFullSyncJobId()).isNull();
    assertThat(state.getIncrementalAnchor()).isNull();
    assertThat(state.completedScopeKeys()).isEmpty();
    assertThat(state.isFullSyncDue(Duration.ofDays(7), done))
        .as("without an anchor no incremental run can follow")
        .isTrue();
  }

  @Test
  void aFullSyncIsDueWithoutACompletedOneAfterAnInterruptionAndOnceTheIntervalPassed() {
    Instant now = Instant.parse("2026-09-10T10:00:00Z");
    Duration weekly = Duration.ofDays(7);
    SourceSyncState state = new SourceSyncState(UUID.randomUUID());
    assertThat(state.isFullSyncDue(weekly, now)).as("never completed").isTrue();

    state.beginFullSync(UUID.randomUUID());
    assertThat(state.isFullSyncDue(weekly, now)).as("in progress / interrupted").isTrue();

    Instant completedAt = now.minus(Duration.ofDays(1));
    state.completeFullSync(completedAt, completedAt);
    assertThat(state.isFullSyncDue(weekly, now)).as("one day after completion").isFalse();
    assertThat(state.isFullSyncDue(weekly, completedAt.plus(weekly)))
        .as("exactly at the interval it is due again")
        .isTrue();
    assertThat(state.isFullSyncDue(weekly, completedAt.plus(weekly).minusSeconds(1)))
        .as("a second before the interval it is not")
        .isFalse();

    state.advanceIncrementalAnchor(now);
    assertThat(state.getIncrementalAnchor()).isEqualTo(now);
    assertThat(state.getFullSyncCompletedAt())
        .as("the anchor does not restart the interval")
        .isEqualTo(completedAt);
  }

  @Test
  void aNewRunAfterAnInterruptionKeepsTheCompletedScopesButAfterACompletionStartsOver() {
    SourceSyncState state = new SourceSyncState(UUID.randomUUID());
    UUID first = UUID.randomUUID();
    state.beginFullSync(first);
    state.markScopeCompleted("ENG");

    UUID second = UUID.randomUUID();
    state.beginFullSync(second);
    assertThat(state.getFullSyncJobId()).isEqualTo(second);
    assertThat(state.completedScopeKeys()).as("resumed").containsExactly("ENG");

    state.markScopeCompleted("HR");
    state.completeFullSync(Instant.now(), Instant.now());
    state.beginFullSync(UUID.randomUUID());
    assertThat(state.completedScopeKeys()).as("clean after a completed sync").isEmpty();
  }
}
