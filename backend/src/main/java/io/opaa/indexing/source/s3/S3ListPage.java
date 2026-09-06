package io.opaa.indexing.source.s3;

import java.util.List;

/**
 * One page of a listing.
 *
 * @param nextContinuationToken the token for the following page, {@code null} on the last one
 */
public record S3ListPage(List<S3ObjectSummary> objects, String nextContinuationToken) {

  public S3ListPage {
    objects = List.copyOf(objects);
  }

  public boolean isLast() {
    return nextContinuationToken == null;
  }
}
