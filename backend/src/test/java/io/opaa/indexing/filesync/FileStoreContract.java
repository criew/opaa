package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The connector contract of a file store, run through {@link FileSync} against the store a subclass
 * supplies: complete and incomplete enumeration over several pages, change detection, deletion by
 * absence, the failure kinds and the protocol categories. Every file connector extends it once per
 * test level it has - a test double and, where one exists, a substitute system in a container.
 */
public abstract class FileStoreContract {

  /** The page size every scenario lists with, small enough that several pages occur. */
  protected static final int PAGE_SIZE = 3;

  /** The store under test over two containers, {@code 0} and {@code 1}, filled by the contract. */
  protected interface Fixture {

    void put(int container, String name, String text) throws Exception;

    void put(int container, String name, byte[] bytes, String mediaType) throws Exception;

    void remove(int container, String name) throws Exception;

    /** From now on {@code container} cannot be listed with the store's credentials. */
    void denyListing(int container) throws Exception;

    /** From now on no file of {@code container} can be read; listing still works. */
    void denyReading(int container) throws Exception;

    /** From now on the store's credentials are refused for every request. */
    void rejectCredentials() throws Exception;

    /**
     * Renames or moves the file or folder {@code from} to {@code to} within {@code container}; only
     * a store under {@link FileStoreFolderContract} needs it.
     */
    default void move(int container, String from, String to) throws Exception {
      throw new UnsupportedOperationException("this fixture cannot move");
    }

    /**
     * Notes a change of {@code name} in {@code container}'s change log; only a store under {@link
     * FileStoreChangeFeedContract} needs it.
     */
    default void changed(int container, String name) throws Exception {
      throw new UnsupportedOperationException("this fixture has no change log");
    }

    /**
     * Moves {@code name} unchanged, with its identity, from container {@code from} to {@code to}
     * and notes the change in both containers' change logs.
     */
    default void moveAcross(int from, String name, int to) throws Exception {
      throw new UnsupportedOperationException("this fixture cannot move across containers");
    }

    /** From now on the store accepts no stored cursor. */
    default void expireCursors() throws Exception {
      throw new UnsupportedOperationException("this fixture has no change log");
    }

    String containerKey(int container);

    String filePath(int container, String name);

    /** A fresh store for one run listing {@code pageSize} entries per page, closed by the run. */
    FileStore open(int pageSize) throws Exception;
  }

  /** A fresh fixture with both containers empty. */
  protected abstract Fixture fixture() throws Exception;

  protected Fixture fixture;
  protected FileSyncHarness harness;

  @BeforeEach
  void setUpContract() throws Exception {
    fixture = fixture();
    harness = new FileSyncHarness();
  }

  protected FileSyncHarness.Run fullSync() throws Exception {
    return harness.fullSync(fixture.open(PAGE_SIZE));
  }

  @Test
  void aCompleteEnumerationFetchesEveryFileAndReportsTheListingComplete() throws Exception {
    fixture.put(0, "a.txt", "Erster Text.");
    fixture.put(0, "q1/b.txt", "Zweiter Text.");
    fixture.put(1, "c.txt", "Dritter Text.");

    FileSyncHarness.Run run = fullSync();

    assertThat(run.failure()).isNull();
    assertThat(run.ingested())
        .containsExactlyInAnyOrder(
            fixture.filePath(0, "a.txt"),
            fixture.filePath(0, "q1/b.txt"),
            fixture.filePath(1, "c.txt"));
    assertThat(run.listingComplete()).isTrue();
    assertThat(run.unlistedContainerKeys()).isEmpty();
    assertThat(run.processed()).isEqualTo(3);
  }

  @Test
  void aListingOverSeveralPagesIsFollowedToItsLastPage() throws Exception {
    List<String> names =
        List.of("d1.txt", "d2.txt", "d3.txt", "d4.txt", "d5.txt", "d6.txt", "d7.txt");
    for (String name : names) {
      fixture.put(0, name, "Inhalt von " + name);
    }

    FileSyncHarness.Run first = fullSync();

    assertThat(first.ingested())
        .as("every page of %s entries per page is listed", PAGE_SIZE)
        .containsExactlyInAnyOrderElementsOf(
            names.stream().map(name -> fixture.filePath(0, name)).toList());
    assertThat(first.listingComplete()).isTrue();

    fixture.remove(0, "d7.txt");
    FileSyncHarness.Run second = fullSync();

    assertThat(second.eventsOf(IndexingEventCategory.REMOVED))
        .as("only the file gone from the last page is removed")
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(fixture.filePath(0, "d7.txt"));
    assertThat(harness.storedPaths()).hasSize(6);
  }

  @Test
  void anIncompleteEnumerationNamesTheContainerAndKeepsTheWholeBestand() throws Exception {
    fixture.put(0, "a.txt", "Erster Text.");
    fixture.put(0, "b.txt", "Zweiter Text.");
    fixture.put(1, "c.txt", "Dritter Text.");
    fullSync();

    fixture.remove(0, "b.txt");
    fixture.denyListing(1);
    FileSyncHarness.Run run = fullSync();

    assertThat(run.failure()).isNull();
    assertThat(run.listingComplete()).isFalse();
    assertThat(run.unlistedContainerKeys()).containsExactly(fixture.containerKey(1));
    assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(fixture.containerKey(1));
    assertThat(harness.storedPaths())
        .as("no deletion without a complete listing")
        .containsExactlyInAnyOrder(
            fixture.filePath(0, "a.txt"),
            fixture.filePath(0, "b.txt"),
            fixture.filePath(1, "c.txt"));
  }

  @Test
  void anUnchangedFileIsNotFetchedAgainAndAChangedOneIs() throws Exception {
    fixture.put(0, "gleich.txt", "Bleibt, wie es ist.");
    fixture.put(0, "anders.txt", "Erste Fassung.");
    fullSync();

    fixture.put(0, "anders.txt", "Zweite, längere Fassung.");
    FileSyncHarness.Run run = fullSync();

    assertThat(run.ingested()).containsExactly(fixture.filePath(0, "anders.txt"));
    assertThat(run.processed()).isEqualTo(1);
    assertThat(run.skipped()).isEqualTo(1);
    assertThat(harness.stored(fixture.filePath(0, "gleich.txt"))).isPresent();
  }

  @Test
  void aFileAbsentFromACompleteListingIsRemoved() throws Exception {
    fixture.put(0, "bleibt.txt", "Bleibt.");
    fixture.put(1, "geht.txt", "Geht.");
    fullSync();

    fixture.remove(1, "geht.txt");
    FileSyncHarness.Run run = fullSync();

    assertThat(run.listingComplete()).isTrue();
    assertThat(run.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(fixture.filePath(1, "geht.txt"));
    assertThat(harness.storedPaths()).containsExactly(fixture.filePath(0, "bleibt.txt"));
  }

  @Test
  void aFileGoneBetweenListingAndDownloadIsAbsentAndNotFetched() throws Exception {
    fixture.put(0, "bleibt.txt", "Bleibt.");
    fixture.put(0, "weg.txt", "Verschwindet beim Abruf.");
    String gone = fixture.filePath(0, "weg.txt");

    FileSyncHarness.Run run =
        harness.fullSync(
            new RemovingBeforeFetch(
                fixture.open(PAGE_SIZE), gone, () -> fixture.remove(0, "weg.txt")));

    assertThat(run.ingested()).containsExactly(fixture.filePath(0, "bleibt.txt"));
    assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(gone);
    assertThat(run.listingComplete()).isTrue();
    assertThat(harness.storedPaths()).containsExactly(fixture.filePath(0, "bleibt.txt"));
  }

  @Test
  void anUnreadableFileKeepsItsStoredVersion() throws Exception {
    fixture.put(0, "offen.txt", "Offen.");
    fixture.put(1, "gesperrt.txt", "Erste Fassung.");
    fullSync();
    String locked = fixture.filePath(1, "gesperrt.txt");
    String storedMarker = harness.stored(locked).orElseThrow().getLastModifiedRemote();

    fixture.put(1, "gesperrt.txt", "Zweite, längere Fassung.");
    fixture.denyReading(1);
    FileSyncHarness.Run run = fullSync();

    assertThat(run.failure()).isNull();
    assertThat(run.ingested()).isEmpty();
    assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(locked);
    assertThat(run.listingComplete()).isTrue();
    assertThat(harness.stored(locked).orElseThrow().getLastModifiedRemote())
        .isEqualTo(storedMarker);
  }

  @Test
  void refusedCredentialsFailTheRunAndKeepTheBestand() throws Exception {
    fixture.put(0, "a.txt", "Erster Text.");
    fixture.put(1, "b.txt", "Zweiter Text.");
    fullSync();

    fixture.remove(1, "b.txt");
    fixture.rejectCredentials();
    FileSyncHarness.Run run = fullSync();

    assertThat(run.failure()).isNotBlank();
    assertThat(run.listingComplete()).isNull();
    assertThat(harness.storedPaths())
        .containsExactlyInAnyOrder(fixture.filePath(0, "a.txt"), fixture.filePath(1, "b.txt"));
  }

  @Test
  void theProtocolNamesEachSkippedFileInItsCategoryAndSummarisesTheRun() throws Exception {
    fixture.put(0, "text.txt", "Ein Text.");
    fixture.put(0, "foto.png", new byte[64], "image/png");
    fixture.put(0, "riesig.txt", new byte[(int) FileSyncHarness.MAX_FILE_SIZE * 4], "text/plain");

    FileSyncHarness.Run run = fullSync();

    assertThat(run.eventsOf(IndexingEventCategory.UNSUPPORTED_FORMAT))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(fixture.filePath(0, "foto.png"));
    assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(fixture.filePath(0, "riesig.txt"));
    assertThat(run.eventsOf(IndexingEventCategory.SUMMARY)).hasSize(1);
    assertThat(run.ingested()).containsExactly(fixture.filePath(0, "text.txt"));
    assertThat(run.skipped()).isEqualTo(2);
    assertThat(run.listingComplete())
        .as("a skipped file is still present: the listing stays complete")
        .isTrue();
  }

  /** Removes one file at the source right before its download, after the listing showed it. */
  private record RemovingBeforeFetch(FileStore store, String filePath, Removal removal)
      implements FileStore {

    @FunctionalInterface
    interface Removal {
      void run() throws Exception;
    }

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
    public FetchedFile fetch(FileEntry entry, long maxBytes)
        throws FileAccessException, InterruptedException {
      if (entry.filePath().equals(filePath)) {
        try {
          removal.run();
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }
      return store.fetch(entry, maxBytes);
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
    public void close() {
      store.close();
    }
  }
}
