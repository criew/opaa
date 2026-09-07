package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link S3SyncState}: resumes the scopes of an interrupted full sync, starts clean otherwise. */
class S3SyncStateTest {

  @Test
  void resumesAnInterruptedFullSyncAndStartsCleanAfterACompletedOne() {
    S3SyncState state = new S3SyncState(UUID.randomUUID());
    assertThat(state.isFullSyncInterrupted()).isFalse();

    UUID first = UUID.randomUUID();
    state.beginFullSync(first);
    state.markScopeCompleted("dokumente/2025/");
    state.markScopeCompleted("satzungen");
    state.markScopeCompleted("dokumente/2025/");
    assertThat(state.isFullSyncInterrupted()).isTrue();
    assertThat(state.completedScopeKeys()).containsExactly("dokumente/2025/", "satzungen");

    UUID second = UUID.randomUUID();
    state.beginFullSync(second);
    assertThat(state.getFullSyncJobId()).isEqualTo(second);
    assertThat(state.completedScopeKeys())
        .as("a resumed run keeps what the interrupted one finished")
        .containsExactly("dokumente/2025/", "satzungen");

    Instant done = Instant.parse("2026-09-06T20:00:00Z");
    state.completeFullSync(done);
    assertThat(state.isFullSyncInterrupted()).isFalse();
    assertThat(state.getFullSyncCompletedAt()).isEqualTo(done);
    assertThat(state.completedScopeKeys()).isEmpty();

    state.beginFullSync(UUID.randomUUID());
    assertThat(state.completedScopeKeys()).as("clean after a completed sync").isEmpty();
  }
}
