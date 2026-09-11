/**
 * Access layer of the S3 connector (ADR-0027): the port {@link
 * io.opaa.indexing.source.s3.S3ObjectStore} with its one adapter on the AWS SDK for Java v2, the
 * value objects a library's S3 configuration is made of ({@link
 * io.opaa.indexing.source.s3.S3Scope}, {@link io.opaa.indexing.source.s3.S3Credentials}, {@link
 * io.opaa.indexing.source.s3.S3Connection}) and the German, credential-free failures every caller
 * sees ({@link io.opaa.indexing.source.s3.S3AccessException}).
 *
 * <p>The SDK brings its own HTTP client and bypasses {@code io.opaa.sourceaccess}; what that
 * package enforces centrally for the HTTP connectors - target validation, byte ceilings, timeouts,
 * proxy, relaxed TLS, retries - is rebuilt here deliberately, piece by piece, with a test per
 * piece. The client build itself ({@link io.opaa.indexing.source.s3.S3SdkClient} from {@link
 * io.opaa.indexing.source.s3.S3ClientSettings}), the per-request guard ({@link
 * io.opaa.indexing.source.s3.S3RequestGuard}) and the failure translation ({@link
 * io.opaa.indexing.source.s3.S3FailureTranslator}) are shared with the S3 storage of uploaded
 * originals in {@code io.opaa.library} (ADR-0030, Entscheidung 8); the request meter and the
 * budget stay with the connector's adapter.
 */
package io.opaa.indexing.source.s3;
