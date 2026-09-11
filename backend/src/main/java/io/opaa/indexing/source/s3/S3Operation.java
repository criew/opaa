package io.opaa.indexing.source.s3;

/**
 * The S3 call a failure is translated in the light of ({@link S3FailureTranslator}): a {@code 403}
 * names the right missing permission and a {@code 404} the right missing thing only when the
 * operation is known. {@code german} is the operation's name in a user-facing message.
 */
public enum S3Operation {
  LIST_OBJECTS("die Auflistung", Kind.BUCKET),
  HEAD_BUCKET("die Bucket-Prüfung", Kind.BUCKET),
  LIST_BUCKETS("die Bucket-Liste", Kind.BUCKET),
  HEAD_OBJECT("die Objektprüfung", Kind.READ),
  GET_OBJECT("den Download", Kind.READ),
  PUT_OBJECT("das Ablegen", Kind.WRITE),
  DELETE_OBJECT("das Löschen", Kind.WRITE);

  /** What a {@code 403}/{@code 404} on the operation is about. */
  enum Kind {
    BUCKET,
    READ,
    WRITE
  }

  private final String german;
  private final Kind kind;

  S3Operation(String german, Kind kind) {
    this.german = german;
    this.kind = kind;
  }

  String german() {
    return german;
  }

  /** The {@link S3AccessException} a {@code 403} on this operation means. */
  S3AccessException forbidden(String bucket, String key) {
    return switch (kind) {
      case BUCKET -> new S3AccessException.ListForbidden(bucket);
      case READ -> new S3AccessException.ReadForbidden(bucket, key);
      case WRITE ->
          new S3AccessException.WriteForbidden(
              bucket, key, this == PUT_OBJECT ? "s3:PutObject" : "s3:DeleteObject");
    };
  }

  /** The {@link S3AccessException} a {@code 404} without an error code on this operation means. */
  S3AccessException notFound(String bucket, String key) {
    return switch (this) {
      case HEAD_OBJECT, GET_OBJECT, DELETE_OBJECT ->
          new S3AccessException.ObjectNotFound(bucket, key);
      case LIST_OBJECTS, HEAD_BUCKET, LIST_BUCKETS, PUT_OBJECT ->
          new S3AccessException.BucketNotFound(bucket);
    };
  }
}
