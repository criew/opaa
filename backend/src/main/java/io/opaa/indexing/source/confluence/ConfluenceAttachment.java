package io.opaa.indexing.source.confluence;

import java.net.URI;

/**
 * An attachment of a page.
 *
 * @param downloadUrl the absolute address to fetch the bytes from, exactly as the instance handed
 *     it out (Cloud appends volatile query parameters such as {@code modificationDate} and {@code
 *     cacheVersion} that change with every version)
 * @param stableUrl {@link #downloadUrl} without its query - stable across versions and unique per
 *     instance, and therefore the attachment document's {@code file_path} (ADR-0022, Entscheidung
 *     2); the version itself is the change marker, not part of the identity
 * @param mediaType the instance's media type, {@code null} if it did not say
 * @param fileSize bytes as reported by the instance, {@code -1} if unknown
 */
public record ConfluenceAttachment(
    String id,
    String pageId,
    String fileName,
    String mediaType,
    long fileSize,
    int version,
    String downloadUrl,
    String stableUrl) {

  static ConfluenceAttachment of(
      String id,
      String pageId,
      String fileName,
      String mediaType,
      long fileSize,
      int version,
      String downloadUrl) {
    return new ConfluenceAttachment(
        id, pageId, fileName, mediaType, fileSize, version, downloadUrl, stripQuery(downloadUrl));
  }

  static String stripQuery(String url) {
    if (url == null) {
      return null;
    }
    try {
      URI uri = URI.create(url);
      return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null).toString();
    } catch (Exception e) {
      int q = url.indexOf('?');
      return q < 0 ? url : url.substring(0, q);
    }
  }
}
