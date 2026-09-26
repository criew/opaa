/**
 * Access layer of the S3 connector (ADR-0027): the port {@link
 * io.opaa.indexing.source.s3.S3ObjectStore} with its one adapter on the AWS SDK for Java v2,
 * listing, full sync, run executor and event intake. The scopes a library's S3 configuration is
 * made of live with the library entity in {@code io.opaa.knowledge.sourcesettings}.
 *
 * <p>Client build, connection values, per-request guard, failure translation and the German,
 * credential-free failures come from {@code io.opaa.s3}, shared with the S3 storage of uploaded
 * originals (ADR-0030, Entscheidung 8). What stays here: the byte ceiling while a download streams,
 * the request meter and the run's request budget.
 */
package io.opaa.indexing.source.s3;
