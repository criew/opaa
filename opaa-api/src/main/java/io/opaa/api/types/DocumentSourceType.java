package io.opaa.api.types;

/**
 * Where a document's bytes come from. Each value states the properties every consumer derives from
 * it: whether the bytes live on a remote only a connector run can re-read ({@link #isRemote}),
 * whether an indexing run exists for it at all ({@link #hasIndexingRun}), and whether a remote
 * document's {@code filePath} is an address a browser can open ({@link #hasDeepLink}) - so a new
 * source type is described here once instead of being enumerated at every consumer.
 */
public enum DocumentSourceType {
  FILESYSTEM(false, true),
  HTTP_DIRECTORY(true, true),
  UPLOAD(false, false),
  RSS_FEED(true, true),
  CONFLUENCE(true, true),
  /**
   * An S3-compatible object store (ADR-0027): remote, but {@code s3://bucket/key} opens nowhere.
   */
  S3(true, true, false);

  private final boolean remote;
  private final boolean indexingRun;
  private final boolean deepLink;

  DocumentSourceType(boolean remote, boolean indexingRun) {
    this(remote, indexingRun, remote);
  }

  DocumentSourceType(boolean remote, boolean indexingRun, boolean deepLink) {
    this.remote = remote;
    this.indexingRun = indexingRun;
    this.deepLink = deepLink;
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

  /**
   * Whether a document's {@code filePath} is an address a reader can open - true for the HTTP-based
   * remote types, false for a local path and for {@code s3://} identities (ADR-0027, Entscheidung
   * 5: no citation link in the first increment).
   */
  public boolean hasDeepLink() {
    return deepLink;
  }
}
