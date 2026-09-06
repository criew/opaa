package io.opaa.library;

import java.net.URI;
import java.util.UUID;

/**
 * Domain counterpart of {@code S3BucketListRequest} (#1376): what a caller hands in to list the
 * buckets an S3 key may see. {@code libraryId} names an existing library whose stored credentials
 * may stand in for omitted ones (same-origin rule, see {@link SourceConnectionTestService}).
 */
public record S3BucketListing(
    URI sourceUrl,
    String sourceCredentials,
    String sourceProxy,
    Boolean sourceInsecureSsl,
    String region,
    Boolean pathStyle,
    UUID libraryId) {}
