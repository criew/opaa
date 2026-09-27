package io.opaa.indexing.source;

/**
 * Optional ability of a {@link SourceConnector}: lists what a source offers for selection before
 * the configuration is saved - the buckets of an object store, the spaces of a wiki. The caller has
 * checked permissions and filled in stored credentials; the browser refuses a caller mistake with
 * {@link io.opaa.common.ValidationException} and passes a source problem on the same way, since a
 * listing has no result without the source.
 */
public interface SourceBrowser {

  /** The German 400 message for a stored library of another type. */
  String otherTypeMessage();

  SourceListing browse(Query query);

  /**
   * @param settings the effective connection - the request's, or the stored library's where the
   *     same-origin rule lets it stand in - with the listing's own parameters as its connector
   *     settings
   * @param stored the connector settings of the stored library the listing is for, {@code null}
   *     before one exists
   */
  record Query(SourceSettings settings, ConnectorData stored) {}
}
