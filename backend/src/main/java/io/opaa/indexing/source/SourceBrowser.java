package io.opaa.indexing.source;

/**
 * Optional ability of a {@link SourceConnector}: lists what a source offers for selection before
 * the configuration is saved - the buckets of an object store, the spaces of a wiki. The caller has
 * checked permissions and filled in stored credentials; the browser refuses a caller mistake with
 * {@link io.opaa.common.ValidationException} and passes a source problem on the same way, since a
 * listing has no result without the source.
 */
public interface SourceBrowser {

  /** What this browser lists; at most one connector offers each kind. */
  Kind browseKind();

  /** The German 400 message for a stored library of another type. */
  String otherTypeMessage();

  SourceListing browse(Query query);

  /** The kinds of listing a connector may offer. */
  enum Kind {
    BUCKETS,
    SPACES
  }

  /**
   * @param settings the effective connection - the request's, or the stored library's where the
   *     same-origin rule lets it stand in
   * @param region the signing region the request names, {@code null} to take the stored one
   * @param pathStyle the addressing style the request names, {@code null} to take the stored one
   */
  record Query(SourceSettings settings, String region, Boolean pathStyle) {}
}
