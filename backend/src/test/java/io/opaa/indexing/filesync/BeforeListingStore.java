package io.opaa.indexing.filesync;

import io.opaa.sourceaccess.SourceRequestMeter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Delegates to {@code store} and runs {@code action} once, right before the first page of the
 * container {@code containerKey} is listed or resumed - something that happens to the bestand while
 * a run is under way.
 */
final class BeforeListingStore implements FileStore {

  private final FileStore store;
  private final String containerKey;
  private Runnable action;

  BeforeListingStore(FileStore store, String containerKey, Runnable action) {
    this.store = store;
    this.containerKey = containerKey;
    this.action = action;
  }

  @Override
  public List<FileContainer> containers() {
    return store.containers();
  }

  @Override
  public void recall(FileContainer container, Map<String, String> subtreeMarkers) {
    store.recall(container, subtreeMarkers);
  }

  @Override
  public FilePage list(FileContainer container, String continuation)
      throws FileAccessException, InterruptedException {
    if (action != null && continuation == null && container.key().equals(containerKey)) {
      Runnable once = action;
      action = null;
      once.run();
    }
    return store.list(container, continuation);
  }

  @Override
  public FilePage resume(FileContainer container, String checkpoint)
      throws FileAccessException, InterruptedException {
    if (action != null && container.key().equals(containerKey)) {
      Runnable once = action;
      action = null;
      once.run();
    }
    return store.resume(container, checkpoint);
  }

  @Override
  public AbsenceProof absenceProof() {
    return store.absenceProof();
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
