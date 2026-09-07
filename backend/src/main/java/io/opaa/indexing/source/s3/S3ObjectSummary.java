package io.opaa.indexing.source.s3;

import java.time.Instant;

/**
 * One entry of a {@code ListObjectsV2} page. The listing carries no content type - that costs a
 * {@link S3ObjectStore#headObject} (ADR-0027, Entscheidung 5).
 *
 * @param eTag the entity tag without its surrounding quotes; {@code null} when the store sent none
 * @param storageClass the store's own class name ({@code STANDARD}, {@code GLACIER}, ...); {@code
 *     null} when the store sent none
 */
public record S3ObjectSummary(
    String key, String eTag, long size, Instant lastModified, String storageClass) {

  /** A zero-byte key ending in {@code /}: a folder marker, never a document. */
  public boolean isFolderMarker() {
    return key.endsWith("/") && size == 0;
  }

  /** Whether the class needs a restore before the object can be read. */
  public boolean isArchived() {
    return S3ArchiveState.isArchiveClass(storageClass);
  }

  /** The last path segment of the key. */
  public String fileName() {
    int slash = key.lastIndexOf('/');
    return slash < 0 ? key : key.substring(slash + 1);
  }
}
