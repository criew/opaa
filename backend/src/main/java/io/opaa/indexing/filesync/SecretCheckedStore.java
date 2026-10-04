package io.opaa.indexing.filesync;

import io.opaa.indexing.source.RunCredentials;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A run's {@link FileStore} that asks the run's {@link RunCredentials} before every access, its
 * change feed included, so a store whose client holds the secret itself still ends the run at its
 * next access once the secret is discarded or the source blocked. Never closes the store it wraps.
 */
final class SecretCheckedStore implements FileStore {

  private final FileStore store;
  private final RunCredentials credentials;

  SecretCheckedStore(FileStore store, RunCredentials credentials) {
    this.store = store;
    this.credentials = credentials;
  }

  @Override
  public List<FileContainer> containers() {
    credentials.check();
    return store.containers();
  }

  @Override
  public void recall(FileContainer container, Map<String, String> subtreeMarkers) {
    store.recall(container, subtreeMarkers);
  }

  @Override
  public FilePage list(FileContainer container, String continuation)
      throws FileAccessException, InterruptedException {
    credentials.check();
    return store.list(container, continuation);
  }

  @Override
  public FileEntry head(FileContainer container, String id)
      throws FileAccessException, InterruptedException {
    credentials.check();
    return store.head(container, id);
  }

  @Override
  public FetchedFile fetch(FileEntry entry, long maxBytes)
      throws FileAccessException, InterruptedException {
    credentials.check();
    return store.fetch(entry, maxBytes);
  }

  @Override
  public Optional<ChangeFeed> changes() {
    return store.changes().map(CheckedFeed::new);
  }

  @Override
  public SourceRequestMeter meter() {
    return store.meter();
  }

  @Override
  public void close() {
    // the store belongs to the executor that opened it
  }

  private final class CheckedFeed implements ChangeFeed {

    private final ChangeFeed feed;

    private CheckedFeed(ChangeFeed feed) {
      this.feed = feed;
    }

    @Override
    public String feedKey(FileContainer container) {
      return feed.feedKey(container);
    }

    @Override
    public String startCursor(String feedKey) throws FileAccessException, InterruptedException {
      credentials.check();
      return feed.startCursor(feedKey);
    }

    @Override
    public ChangePage read(String feedKey, String cursor)
        throws FileAccessException, InterruptedException {
      credentials.check();
      return feed.read(feedKey, cursor);
    }

    @Override
    public void requireReachable(FileContainer container)
        throws FileAccessException, InterruptedException {
      credentials.check();
      feed.requireReachable(container);
    }
  }
}
