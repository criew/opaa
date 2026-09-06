package io.opaa.indexing.source.s3.events;

/**
 * One object an S3 event notification names (ADR-0027, Entscheidung 6). The kind is a hint only:
 * every object is checked with {@code HeadObject}, and the store's answer is the finding.
 *
 * @param key the object key as the store holds it (decoded where the format encodes it)
 */
public record S3ObjectEvent(String bucket, String key, Kind kind) {

  public enum Kind {
    CREATED,
    REMOVED,
    OTHER
  }

  /** {@code bucket/key} - the form the intake queues and the run reads. */
  public String reference() {
    return bucket + "/" + key;
  }
}
