package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The change run of {@link FileSync} (ADR-0040, Entscheidung 6): reported files go the full sync's
 * way, reported removals count only while the stream's containers are reachable, a stream's new
 * cursor is kept only after a clean read, and a change of structure, an expired cursor or a missing
 * one make the next run a full sync.
 */
class FileSyncChangeRunTest {

  private static final Set<String> STREAMS = Set.of("stream:A", "stream:B");
  private static final Duration WEEK = Duration.ofDays(7);

  private FileSyncHarness harness;
  private InMemoryFileStore store;

  @BeforeEach
  void fullSyncFirst() throws Exception {
    harness = new FileSyncHarness();
    store =
        new InMemoryFileStore()
            .container("A")
            .container("B")
            .withChangeFeed()
            .put("A", "a.txt", "Erste Fassung.")
            .put("A", "b.txt", "Bleibt nicht.")
            .put("B", "c.txt", "Dritter Text.");
    harness.fullSync(store);
    assertThat(harness.state().canReadChanges(STREAMS, WEEK, FileSyncHarness.NOW)).isTrue();
  }

  @Test
  void changedAndNewFilesAreFetchedRemovedOnesRemovedAndTheCursorsMoveOn() {
    Map<String, String> before = harness.state().changeCursors();
    store
        .put("A", "a.txt", "Zweite, längere Fassung.")
        .changed("A", "a.txt")
        .remove("A", "b.txt")
        .changed("A", "b.txt")
        .put("A", "neu/d.txt", "Neu.")
        .changed("A", "neu/d.txt");

    FileSyncHarness.Run run = harness.changeRun(store.reset());

    assertThat(run.failure()).isNull();
    assertThat(store.calls()).noneMatch(call -> call.startsWith("list"));
    assertThat(run.ingested())
        .containsExactlyInAnyOrder(
            InMemoryFileStore.filePath("A", "a.txt"), InMemoryFileStore.filePath("A", "neu/d.txt"));
    assertThat(run.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(InMemoryFileStore.filePath("A", "b.txt"));
    assertThat(run.listingComplete()).as("a change run reconciles nothing").isNull();
    assertThat(harness.storedPaths()).hasSize(3);
    assertThat(harness.state().changeCursors().get("stream:A"))
        .isNotEqualTo(before.get("stream:A"));
    assertThat(harness.state().canReadChanges(STREAMS, WEEK, FileSyncHarness.NOW)).isTrue();
  }

  @Test
  void anUnchangedFileReportedAgainIsNotFetched() {
    store.changed("B", "c.txt");

    FileSyncHarness.Run run = harness.changeRun(store.reset());

    assertThat(run.ingested()).isEmpty();
    assertThat(run.skipped()).isEqualTo(1);
    assertThat(store.calls()).noneMatch(call -> call.startsWith("fetch"));
  }

  @Test
  void aRemovalInAStreamWithAnUnreachableContainerIsNoFindingAndTheCursorStays() {
    Map<String, String> before = harness.state().changeCursors();
    store.remove("B", "c.txt").changed("B", "c.txt").denyListing("B");

    FileSyncHarness.Run run = harness.changeRun(store.reset());

    assertThat(run.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly("B");
    assertThat(harness.storedPaths()).contains(InMemoryFileStore.filePath("B", "c.txt"));
    assertThat(harness.state().changeCursors().get("stream:B")).isEqualTo(before.get("stream:B"));
  }

  @Test
  void aFileThatFailedKeepsTheCursorSoTheNextRunReadsItAgain() {
    Map<String, String> before = harness.state().changeCursors();
    store.put("A", "a.txt", "Zweite, längere Fassung.").changed("A", "a.txt");

    FileSyncHarness.Run run = harness.changeRun(new FailingFetch(store.reset()));

    assertThat(run.failed()).isEqualTo(1);
    assertThat(harness.state().changeCursors().get("stream:A")).isEqualTo(before.get("stream:A"));

    FileSyncHarness.Run retried = harness.changeRun(store.reset());

    assertThat(retried.ingested()).containsExactly(InMemoryFileStore.filePath("A", "a.txt"));
    assertThat(harness.state().changeCursors().get("stream:A"))
        .isNotEqualTo(before.get("stream:A"));
  }

  @Test
  void aChangeOfStructureMakesTheNextRunAFullSync() {
    store.put("A", "a.txt", "Zweite, längere Fassung.").changed("A", "a.txt").structureChanged();

    FileSyncHarness.Run run = harness.changeRun(store.reset());

    assertThat(run.ingested()).containsExactly(InMemoryFileStore.filePath("A", "a.txt"));
    assertThat(harness.state().canReadChanges(STREAMS, WEEK, FileSyncHarness.NOW)).isFalse();
    assertThat(harness.state().isFullSyncDue(WEEK, FileSyncHarness.NOW)).isTrue();
  }

  @Test
  void anExpiredCursorDropsTheStreamAndMakesTheNextRunAFullSync() {
    store.expireCursors();

    FileSyncHarness.Run run = harness.changeRun(store.reset());

    assertThat(run.failure()).isNull();
    assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getMessage)
        .allMatch(message -> message.contains("Vollabgleich"))
        .hasSize(2);
    assertThat(harness.state().changeCursors()).isEmpty();
    assertThat(harness.state().canReadChanges(STREAMS, WEEK, FileSyncHarness.NOW)).isFalse();
  }

  @Test
  void aStreamWithoutCursorIsNotReadAndTheNextRunIsAFullSync() {
    harness.state().discardChangeCursor("stream:B");

    harness.changeRun(store.reset());

    assertThat(store.calls()).noneMatch(call -> call.startsWith("read stream:B"));
    assertThat(store.calls()).anyMatch(call -> call.startsWith("read stream:A"));
    assertThat(harness.state().canReadChanges(STREAMS, WEEK, FileSyncHarness.NOW)).isFalse();
  }

  @Test
  void theNextFullSyncAfterAStructureChangeMakesChangesReadableAgain() {
    store.structureChanged();
    harness.changeRun(store.reset());

    harness.fullSync(store.reset());

    assertThat(harness.state().canReadChanges(STREAMS, WEEK, FileSyncHarness.NOW)).isTrue();
  }

  /** Fails every download as a transient error of that one request. */
  private record FailingFetch(FileStore store) implements FileStore {

    @Override
    public List<FileContainer> containers() {
      return store.containers();
    }

    @Override
    public FilePage list(FileContainer container, String continuation)
        throws FileAccessException, InterruptedException {
      return store.list(container, continuation);
    }

    @Override
    public FileEntry head(FileContainer container, String id)
        throws FileAccessException, InterruptedException {
      return store.head(container, id);
    }

    @Override
    public FetchedFile fetch(FileEntry entry, long maxBytes) throws FileAccessException {
      throw new FileAccessException.Transient("Die Anfrage ist gescheitert.");
    }

    @Override
    public Optional<ChangeFeed> changes() {
      return store.changes();
    }

    @Override
    public SourceRequestMeter meter() {
      return store.meter();
    }

    @Override
    public void close() {}
  }
}
