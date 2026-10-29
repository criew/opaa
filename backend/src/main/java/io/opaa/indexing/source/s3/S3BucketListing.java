package io.opaa.indexing.source.s3;

import java.util.List;
import java.util.Map;

/** The outcome of {@link S3ObjectStore#listBuckets()}. */
public sealed interface S3BucketListing {

  /**
   * The bucket names the credentials may see, in the store's order.
   *
   * @param regions the region of each bucket the store named one for - AWS does, MinIO does not
   */
  record Listed(List<String> names, Map<String, String> regions) implements S3BucketListing {
    public Listed {
      names = List.copyOf(names);
      regions = regions == null ? Map.of() : Map.copyOf(regions);
    }

    /** A listing that names no region. */
    public Listed(List<String> names) {
      this(names, Map.of());
    }

    /** The region the store named for {@code bucket}, {@code null} when it named none. */
    public String regionOf(String bucket) {
      return regions.get(bucket);
    }
  }

  /**
   * The credentials lack {@code s3:ListAllMyBuckets} - the normal case for a restricted key on AWS;
   * the wizard falls back to manual entry. Stores like MinIO answer such a key with the filtered
   * list of buckets it may see instead, which arrives as {@link Listed}.
   */
  record NotPermitted() implements S3BucketListing {}
}
