package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.run.ConnectionReconnectedException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A connection connected anew after the run began - perhaps as another account, whose source does
 * not know the run's files - ends the run at its next ask for the secret: a reported file the new
 * account cannot find is not removed.
 */
class FileSyncReconnectionTest {

  private final FileSyncHarness harness;
  private final InMemoryFileStore store;
  private final String gone = InMemoryFileStore.filePath("A", "weg.txt");

  FileSyncReconnectionTest() throws Exception {
    harness = new FileSyncHarness();
    store = new InMemoryFileStore().container("A").put("A", "weg.txt", "W.");
    harness.fullSync(store);
    store.remove("A", "weg.txt");
  }

  // regression guard for #3147: a 404 under a reconnected account proves no deletion
  @Test
  void anEventRunAfterAReconnectionRemovesNothingAndEndsWithItsNote() {
    harness.connectionGenerations("erstes Konto", "zweites Konto");

    FileSyncHarness.Run run = harness.refresh(store.reset(), List.of(reference()));

    assertThat(harness.storedPaths()).containsExactly(gone);
    assertThat(run.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(store.calls()).as("no request with the new secret").isEmpty();
    assertThat(run.failure()).isEqualTo(ConnectionReconnectedException.MESSAGE);
  }

  @Test
  void anEventRunOnAnUnchangedConnectionRemovesAConfirmedDeletion() {
    harness.connectionGenerations("erstes Konto");

    FileSyncHarness.Run run = harness.refresh(store.reset(), List.of(reference()));

    assertThat(run.failure()).isNull();
    assertThat(run.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(gone);
    assertThat(harness.storedPaths()).isEmpty();
  }

  private FileReference reference() {
    return new FileReference(new FileContainer("A"), "weg.txt", gone);
  }
}
