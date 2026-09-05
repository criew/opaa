package io.opaa.api.types;

/**
 * Where a document's bytes come from. Each value states the two properties every consumer derives
 * from it: whether the bytes live on a remote only a connector run can re-read ({@link #isRemote}),
 * and whether an indexing run exists for it at all ({@link #hasIndexingRun}) - so a new source type
 * is described here once instead of being enumerated at every consumer.
 */
public enum DocumentSourceType {
  FILESYSTEM(false, true),
  HTTP_DIRECTORY(true, true),
  UPLOAD(false, false),
  RSS_FEED(true, true),
  CONFLUENCE(true, true);

  private final boolean remote;
  private final boolean indexingRun;

  DocumentSourceType(boolean remote, boolean indexingRun) {
    this.remote = remote;
    this.indexingRun = indexingRun;
  }

  /**
   * Whether a document's {@code filePath} is a remote address (its own deep link) rather than a
   * server-local path - the bytes are reachable again only by the connector run.
   */
  public boolean isRemote() {
    return remote;
  }

  /** Whether an indexing run exists for this type; {@code UPLOAD} is an origin, not a run. */
  public boolean hasIndexingRun() {
    return indexingRun;
  }
}
