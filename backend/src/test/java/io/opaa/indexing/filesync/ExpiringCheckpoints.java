package io.opaa.indexing.filesync;

import io.opaa.sourceaccess.SourceRequestMeter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lets a fixture expire the checkpoints a store gave so far, for a store whose own checkpoints do
 * not expire: every checkpoint carries the generation it was given in, and a resumption from an
 * earlier generation is refused before the store sees it.
 */
public final class ExpiringCheckpoints {

  private int generation;

  /** From now on no checkpoint given so far is accepted. */
  public void expire() {
    generation++;
  }

  public FileStore wrap(FileStore store) {
    return new Store(store);
  }

  private final class Store implements FileStore {

    private final FileStore store;

    private Store(FileStore store) {
      this.store = store;
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
      return marked(store.list(container, continuation));
    }

    @Override
    public FilePage resume(FileContainer container, String checkpoint)
        throws FileAccessException, InterruptedException {
      int bar = checkpoint.indexOf('|');
      if (bar < 0 || Integer.parseInt(checkpoint.substring(1, bar)) != generation) {
        throw new FileAccessException.CheckpointExpired("Der Fortsetzungspunkt ist verfallen.");
      }
      return marked(store.resume(container, checkpoint.substring(bar + 1)));
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

    private FilePage marked(FilePage page) {
      return page.checkpoint() == null
          ? page
          : new FilePage(
              page.entries(),
              page.next(),
              "g" + generation + "|" + page.checkpoint(),
              page.unchangedSubtrees(),
              page.listedSubtrees());
    }
  }
}
