package io.opaa.indexing.source.s3;

import java.time.Instant;
import java.util.Map;

/**
 * The head of one object, as {@link S3ObjectStore#headObject} reports it.
 *
 * @param contentType the stored {@code Content-Type}; {@code null} when the store sent none
 * @param eTag the entity tag without its surrounding quotes; {@code null} when the store sent none
 * @param archived whether the object needs a restore before it can be read ({@link S3ArchiveState})
 * @param metadata the user metadata ({@code x-amz-meta-*}, keys without the prefix) - carried for
 *     the Zielbild of ADR-0024, not evaluated in the first Ausbau
 */
public record S3ObjectHead(
    String contentType,
    String eTag,
    long size,
    Instant lastModified,
    String storageClass,
    boolean archived,
    Map<String, String> metadata) {

  public S3ObjectHead {
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }
}
