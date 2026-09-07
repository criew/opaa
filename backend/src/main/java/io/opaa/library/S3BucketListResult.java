package io.opaa.library;

import java.util.List;

/**
 * What {@link S3ConnectionService#listBuckets} found: the visible buckets, or - for a key without
 * {@code s3:ListAllMyBuckets}, the normal case - {@code permitted} false with the German hint to
 * enter the bucket name by hand.
 */
public record S3BucketListResult(boolean permitted, List<String> buckets, String message) {

  public S3BucketListResult {
    buckets = List.copyOf(buckets);
  }
}
