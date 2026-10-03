package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The contract of a store with a change log (ADR-0040, Entscheidung 6), run through the change run
 * of {@link FileSync}: a change after the start cursor appears, a stream is read to its last page
 * before its cursor moves on, an expired cursor drops the stream, a removal is a deletion finding
 * only for a reachable container, and every stream is read. A store with a change log extends it
 * besides {@link FileStoreContract}; its fixture implements {@link Fixture#changed} and {@link
 * Fixture#expireCursors}.
 */
public abstract class FileStoreChangeFeedContract extends FileStoreContract {

  private FileSyncHarness.Run changeRun() throws Exception {
    return harness.changeRun(fixture.open(PAGE_SIZE));
  }

  @Test
  void aChangeAfterTheStartCursorAppearsInTheNextChangeRun() throws Exception {
    fixture.put(0, "a.txt", "Erster Text.");
    fullSync();
    Map<String, String> before = harness.state().changeCursors();

    fixture.put(0, "neu.txt", "Später angelegt.");
    fixture.changed(0, "neu.txt");
    FileSyncHarness.Run run = changeRun();

    assertThat(run.failure()).isNull();
    assertThat(run.ingested()).containsExactly(fixture.filePath(0, "neu.txt"));
    assertThat(harness.state().changeCursors())
        .as("a clean read moves the cursor on")
        .isNotEqualTo(before);
  }

  @Test
  void aStreamIsReadToItsLastPageBeforeItsCursorMovesOn() throws Exception {
    fixture.put(0, "a.txt", "Erster Text.");
    fullSync();

    List<String> names = new ArrayList<>();
    for (int i = 1; i <= 2 * PAGE_SIZE + 1; i++) {
      String name = "n" + i + ".txt";
      names.add(name);
      fixture.put(0, name, "Inhalt " + i);
      fixture.changed(0, name);
    }
    FileSyncHarness.Run run = changeRun();

    assertThat(run.ingested())
        .as("every page of the stream is read in one run")
        .containsExactlyInAnyOrderElementsOf(
            names.stream().map(name -> fixture.filePath(0, name)).toList());
    assertThat(changeRun().ingested()).as("the new start lies behind every change").isEmpty();
  }

  @Test
  void anExpiredCursorDropsItsStreamAndTheNextRunIsAFullSync() throws Exception {
    fixture.put(0, "a.txt", "Erster Text.");
    fullSync();

    fixture.expireCursors();
    FileSyncHarness.Run run = changeRun();

    assertThat(run.failure()).isNull();
    assertThat(harness.state().changeCursors()).as("the expired streams are dropped").isEmpty();
    assertThat(harness.state().getFullSyncCompletedAt()).as("the next run is a full sync").isNull();
  }

  @Test
  void aRemovedFileIsADeletionFinding() throws Exception {
    fixture.put(0, "bleibt.txt", "Bleibt.");
    fixture.put(0, "geht.txt", "Geht.");
    fullSync();

    fixture.remove(0, "geht.txt");
    fixture.changed(0, "geht.txt");
    FileSyncHarness.Run run = changeRun();

    assertThat(run.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(fixture.filePath(0, "geht.txt"));
    assertThat(harness.storedPaths()).containsExactly(fixture.filePath(0, "bleibt.txt"));
  }

  @Test
  void aRemovalInAnUnreachableContainerIsNoFinding() throws Exception {
    fixture.put(1, "c.txt", "Dritter Text.");
    fullSync();

    fixture.remove(1, "c.txt");
    fixture.changed(1, "c.txt");
    fixture.denyListing(1);
    FileSyncHarness.Run run = changeRun();

    assertThat(run.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.storedPaths()).containsExactly(fixture.filePath(1, "c.txt"));
  }

  /**
   * An unchanged file moved into the other container stays a document there, whichever stream is
   * read first: the removal its old container's stream reports is no finding for it any more.
   */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"0, 1", "1, 0"})
  void aFileMovedBetweenContainersStaysADocument(int from, int to) throws Exception {
    fixture.put(from, "wandert.txt", "Bleibt gleich.");
    fullSync();
    String path = fixture.filePath(from, "wandert.txt");

    fixture.moveAcross(from, "wandert.txt", to);
    FileSyncHarness.Run run = changeRun();

    assertThat(run.failure()).isNull();
    assertThat(run.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.stored(path))
        .as("the document now belongs to container %s", to)
        .hasValueSatisfying(
            document ->
                assertThat(document.getSourceContainerKey()).isEqualTo(fixture.containerKey(to)));
  }

  /** A run that ends while reading a later stream moves no cursor past a reported removal. */
  @Test
  void aRemovalSurvivesARunThatEndsInALaterStream() throws Exception {
    fixture.put(0, "geht.txt", "Geht.");
    fixture.put(1, "b.txt", "Zweiter Text.");
    fullSync();

    fixture.remove(0, "geht.txt");
    fixture.changed(0, "geht.txt");
    FileSyncHarness.Run aborted =
        harness.changeRun(new EndingInSecondStream(fixture.open(PAGE_SIZE)));
    FileSyncHarness.Run next = changeRun();

    assertThat(aborted.failure()).isNotBlank();
    assertThat(harness.storedPaths())
        .as("the next run still reads the removal")
        .doesNotContain(fixture.filePath(0, "geht.txt"));
    assertThat(next.failure()).isNull();
  }

  /** A store whose change log ends the run on the second stream it is asked to read. */
  private record EndingInSecondStream(FileStore store) implements FileStore {

    @Override
    public java.util.List<FileContainer> containers() {
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
    public FetchedFile fetch(FileEntry entry, long maxBytes)
        throws FileAccessException, InterruptedException {
      return store.fetch(entry, maxBytes);
    }

    @Override
    public java.util.Optional<ChangeFeed> changes() {
      ChangeFeed feed = store.changes().orElseThrow();
      java.util.Set<String> read = new java.util.LinkedHashSet<>();
      return java.util.Optional.of(
          new ChangeFeed() {
            @Override
            public String feedKey(FileContainer container) {
              return feed.feedKey(container);
            }

            @Override
            public String startCursor(String feedKey)
                throws FileAccessException, InterruptedException {
              return feed.startCursor(feedKey);
            }

            @Override
            public ChangePage read(String feedKey, String cursor)
                throws FileAccessException, InterruptedException {
              read.add(feedKey);
              if (read.size() > 1) {
                throw new FileAccessException.RunEnding("Die Zugangsdaten wurden abgelehnt.");
              }
              return feed.read(feedKey, cursor);
            }

            @Override
            public void requireReachable(FileContainer container)
                throws FileAccessException, InterruptedException {
              feed.requireReachable(container);
            }
          });
    }

    @Override
    public io.opaa.sourceaccess.SourceRequestMeter meter() {
      return store.meter();
    }

    @Override
    public void close() {
      store.close();
    }
  }

  @Test
  void theChangesOfEveryContainerAreRead() throws Exception {
    fixture.put(0, "a.txt", "Erster Text.");
    fixture.put(1, "b.txt", "Zweiter Text.");
    fullSync();

    fixture.put(0, "a2.txt", "Neu in null.");
    fixture.changed(0, "a2.txt");
    fixture.put(1, "b2.txt", "Neu in eins.");
    fixture.changed(1, "b2.txt");
    FileSyncHarness.Run run = changeRun();

    assertThat(run.ingested())
        .containsExactlyInAnyOrder(fixture.filePath(0, "a2.txt"), fixture.filePath(1, "b2.txt"));
  }
}
