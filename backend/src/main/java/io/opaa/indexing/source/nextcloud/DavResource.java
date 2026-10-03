package io.opaa.indexing.source.nextcloud;

/**
 * One resource of a WebDAV {@code multistatus} answer, as far as the connector reads it.
 *
 * @param href the address as the server sent it, percent-encoded - what a later request uses
 * @param path the decoded absolute path of {@code href}, without a trailing slash
 * @param collection whether it is a folder
 * @param etag the {@code getetag} without quotes, {@code null} when absent
 * @param size the {@code getcontentlength}, {@code -1} when absent
 * @param contentType the {@code getcontenttype}, {@code null} when absent
 * @param fileId the {@code oc:fileid}, {@code null} when absent
 * @param mountType the {@code nc:mount-type} ({@code shared}, {@code group}, ...), {@code null}
 *     when absent or empty
 */
record DavResource(
    String href,
    String path,
    boolean collection,
    String etag,
    long size,
    String contentType,
    String fileId,
    String mountType) {

  /** The last segment of {@link #path()}, {@code ""} for the root. */
  String name() {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }
}
