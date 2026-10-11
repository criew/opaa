package io.opaa.indexing.source.s3;

import java.time.Instant;

/**
 * The change feature of an S3 object (ADR-0027, Entscheidung 4), compared before any download and
 * persisted as {@code documents.last_modified_remote}: {@code e:<ETag>|<size>} whatever the ETag's
 * length, or the fallback {@code t:<LastModified as epoch millis>|<size>} when the store sent no
 * ETag. {@code LastModified} alone is never the feature - a re-upload of the same content renews it
 * without a change.
 */
final class S3ChangeMarker {

  private S3ChangeMarker() {}

  /** The feature of a listed object, or {@code null} when neither form can be built. */
  static String of(S3ObjectSummary object) {
    return of(object.eTag(), object.size(), object.lastModified());
  }

  static String of(String eTag, long size, Instant lastModified) {
    if (eTag != null && !eTag.isBlank()) {
      return "e:" + eTag.strip() + "|" + size;
    }
    if (lastModified == null) {
      return null;
    }
    return "t:" + lastModified.toEpochMilli() + "|" + size;
  }
}
