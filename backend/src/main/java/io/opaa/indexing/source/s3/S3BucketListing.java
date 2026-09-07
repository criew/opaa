package io.opaa.indexing.source.s3;

import java.util.List;

/** The outcome of {@link S3ObjectStore#listBuckets()}. */
public sealed interface S3BucketListing {

  /** The bucket names the credentials may see, in the store's order. */
  record Listed(List<String> names) implements S3BucketListing {
    public Listed {
      names = List.copyOf(names);
    }
  }

  /**
   * The credentials lack {@code s3:ListAllMyBuckets} - the normal case for a restricted key on AWS;
   * the wizard falls back to manual entry. Stores like MinIO answer such a key with the filtered
   * list of buckets it may see instead, which arrives as {@link Listed}.
   */
  record NotPermitted() implements S3BucketListing {}
}
