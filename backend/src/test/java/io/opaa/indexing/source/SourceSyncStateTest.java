package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

    // the anchor is the run's start, the completion time its end - never the same instant
    Instant anchor = Instant.parse("2026-09-03T10:00:00Z");
    Instant completedAt = Instant.parse("2026-09-03T10:42:00Z");
    state.completeFullSync(completedAt, anchor);
    assertThat(state.isFullSyncInterrupted()).isFalse();
    assertThat(state.getIncrementalAnchor()).isEqualTo(anchor);
    assertThat(state.getFullSyncCompletedAt()).isEqualTo(completedAt);
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
  void heldStartCursorsBecomeValidOnCompletionAndAFreshFullSyncDropsThemButNotTheValidOnes() {
    SourceSyncState state = new SourceSyncState(UUID.randomUUID());
    assertThat(state.changeCursors()).isEmpty();
    assertThat(state.pendingChangeCursors()).isEmpty();

    state.beginFullSync(UUID.randomUUID());
    state.holdPendingChangeCursors(Map.of("drive:1", "100", "drive:10", "110"));
    state.beginFullSync(UUID.randomUUID());
    assertThat(state.pendingChangeCursors())
        .as("kept on resumption")
        .containsOnly(Map.entry("drive:1", "100"), Map.entry("drive:10", "110"));
    assertThat(state.changeCursors()).isEmpty();

    state.completeFullSync(Instant.parse("2026-10-03T10:00:00Z"));
    assertThat(state.changeCursors())
        .containsOnly(Map.entry("drive:1", "100"), Map.entry("drive:10", "110"));
    assertThat(state.pendingChangeCursors()).isEmpty();

    state.beginFullSync(UUID.randomUUID());
    state.holdPendingChangeCursors(Map.of("drive:1", "200"));
    state.completeFullSync(Instant.parse("2026-10-03T11:00:00Z"));
    state.beginFullSync(UUID.randomUUID());
    assertThat(state.pendingChangeCursors()).as("a fresh full sync starts without").isEmpty();
    assertThat(state.changeCursors())
        .as("the valid cursors stay until a full sync completes")
        .containsOnly(Map.entry("drive:1", "200"));
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

  @Test
  void aMemoryWrittenUnderTheEarlierBasisStillReadsAndNamesThatBasis() {
    SourceSyncState state = new SourceSyncState(UUID.randomUUID());
    ReflectionTestUtils.setField(
        state,
        "subtreeMarkers",
        "{\"basis\":\"v1|1024|txt\",\"establishedAt\":\"2026-10-03T10:00:00Z\","
            + "\"containers\":{\"A\":{\"\":\"m:1\"}},\"documentCounts\":{\"A\":2}}");

    SourceSyncState.SubtreeMemory memory = state.subtreeMemory();

    assertThat(memory.basis()).isEqualTo("v1|1024|txt");
    assertThat(memory.containers()).containsOnlyKeys("A");
  }
}
