/**
 * The shared S3 client layer (ADR-0027, Entscheidung 8 and 9; ADR-0030, Entscheidung 8): the one
 * way to build an SDK client ({@link io.opaa.s3.S3SdkClient} from {@link
 * io.opaa.s3.S3ClientSettings}), the per-request target check ({@link io.opaa.s3.S3RequestGuard}),
 * the failure translation into German, credential-free {@link io.opaa.s3.S3AccessException}s and
 * the connection values of one object store ({@link io.opaa.s3.S3Connection}, {@link
 * io.opaa.s3.S3Credentials}).
 *
 * <p>The SDK brings its own HTTP client and bypasses {@code io.opaa.sourceaccess}; what that
 * package enforces centrally for the HTTP connectors - target validation, timeouts, proxy, relaxed
 * TLS, retries - is rebuilt here deliberately, with a test per piece. Its users, the S3 connector
 * and the S3 storage of uploaded originals, are unknown to it; request meter and budget are
 * optional inputs owned by the caller.
 */
package io.opaa.s3;
