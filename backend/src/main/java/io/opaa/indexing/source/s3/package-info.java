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
 * proxy, relaxed TLS, retries - the adapter here rebuilds deliberately, piece by piece, with a test
 * per piece.
 */
package io.opaa.indexing.source.s3;
