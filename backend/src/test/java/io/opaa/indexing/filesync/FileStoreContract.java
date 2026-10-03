package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The connector contract of a file store (#1293), run through {@link FileSync} against the store a
 * subclass supplies: complete and incomplete enumeration, change detection, deletion by absence and
 * the protocol categories. Every file connector extends it once per test level it has - a test
 * double and, where one exists, a substitute system in a container.
 */
public abstract class FileStoreContract {

  /** The store under test over two containers, {@code 0} and {@code 1}, filled by the contract. */
  protected interface Fixture {

    void put(int container, String name, String text) throws Exception;

    void put(int container, String name, byte[] bytes, String mediaType) throws Exception;

    void remove(int container, String name) throws Exception;

    /** From now on {@code container} cannot be listed with the store's credentials. */
    void denyListing(int container) throws Exception;

    String containerKey(int container);

    String filePath(int container, String name);

    /** A fresh store for one run, closed by the run. */
    FileStore open() throws Exception;
  }

  /** A fresh fixture with both containers empty. */
  protected abstract Fixture fixture() throws Exception;

  private Fixture fixture;
  private FileSyncHarness harness;

  @BeforeEach
  void setUpContract() throws Exception {
    fixture = fixture();
    harness = new FileSyncHarness();
  }

  private FileSyncHarness.Run fullSync() throws Exception {
    return harness.fullSync(fixture.open());
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
}
