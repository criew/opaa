package io.opaa.indexing.source.s3;

import java.util.Optional;

/**
 * The bucket and key behind a document's {@code file_path} (ADR-0027, Entscheidung 5): {@code
 * s3://<bucket>/<key>}, the key unchanged and not URL-encoded. {@link #filePath} writes that
 * identity, {@link #parse} reads it back, so the shape lives in one place.
 */
public record S3ObjectRef(String bucket, String key) {

  static final String SCHEME = "s3://";

  /** {@code s3://<bucket>/<key>} - the identity per library. */
  public static String filePath(String bucket, String key) {
    return SCHEME + bucket + "/" + key;
  }

  /**
   * The reference {@code filePath} names, or empty when it is not of that shape - an upload's local
   * path, a web directory's URL, a bucket without a key. Whether the key still names an object is
   * the store's answer, not this method's.
   */
  public static Optional<S3ObjectRef> parse(String filePath) {
    if (filePath == null || !filePath.startsWith(SCHEME)) {
      return Optional.empty();
    }
    String remainder = filePath.substring(SCHEME.length());
    int slash = remainder.indexOf('/');
    if (slash <= 0 || slash == remainder.length() - 1) {
      return Optional.empty();
    }
    return Optional.of(
        new S3ObjectRef(remainder.substring(0, slash), remainder.substring(slash + 1)));
  }
}
