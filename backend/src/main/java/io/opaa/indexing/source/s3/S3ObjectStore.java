package io.opaa.indexing.source.s3;

import io.opaa.sourceaccess.SourceRequestMeter;

/**
 * The port every S3 run, the connection test and the bucket listing talk to (ADR-0027, Entscheidung
 * 9) - one adapter on the AWS SDK, created by {@link S3ClientFactory}. An instance is bound to one
 * library's connection, holds an HTTP connection pool and must be closed.
 *
 * <p>Contract, guarded by {@code AwsSdkS3ObjectStoreTest} and the MinIO suite: a listing follows
 * continuation tokens page by page and never a body; {@code 503 SlowDown}/{@code 429} are retried
 * with backoff and counted, never silently; every failure surfaces as an {@link S3AccessException}
 * whose German message names bucket, key and cause but never a credential; the target of every
 * request passes {@code TargetAddressValidator} before it is sent. A run's store refuses the call
 * that would exceed its request budget with a {@link
 * io.opaa.indexing.source.RequestBudgetExhaustedException}, before it is sent.
 */
public interface S3ObjectStore extends AutoCloseable {

  /**
   * One page of {@code scope}'s objects in key order, starting from {@code continuationToken}
   * ({@code null} for the first page). The token is valid only within this store's own listing.
   *
   * @throws S3AccessException.ListForbidden when the credentials may not list the bucket
   * @throws S3AccessException.BucketNotFound when the bucket does not exist at this endpoint
   */
  S3ListPage listObjects(S3Scope scope, String continuationToken)
      throws S3AccessException, InterruptedException;

  /**
   * The head of one object - content type, tag, size, timestamp, storage class, archive state.
   *
   * @throws S3AccessException.ObjectNotFound when there is no current object under {@code key}
   *     (unambiguous only with {@code s3:ListBucket}, which is a required right)
   * @throws S3AccessException.ReadForbidden when the credentials may not read it
   */
  S3ObjectHead headObject(String bucket, String key) throws S3AccessException, InterruptedException;

  /**
   * Downloads one object into a temporary file <b>the caller must delete</b>, capped at {@code
   * maxBytes} while streaming: a declared oversize length is refused before the first byte, an
   * undeclared one the moment the ceiling is crossed - the partial file never survives.
   *
   * @throws S3AccessException.ObjectTooLarge when the object exceeds {@code maxBytes}
   * @throws S3AccessException.Archived when the object lies in an archive class without a restore
   */
  S3Download getObject(String bucket, String key, long maxBytes)
      throws S3AccessException, InterruptedException;

  /** {@link #getObject(String, String, long)} capped at the configured object size bound. */
  S3Download getObject(String bucket, String key) throws S3AccessException, InterruptedException;

  /**
   * The buckets the credentials may see, or {@link S3BucketListing.NotPermitted} when the account
   * lacks {@code s3:ListAllMyBuckets} - restricted keys are the normal case, never an error.
   */
  S3BucketListing listBuckets() throws S3AccessException, InterruptedException;

  /**
   * Probes one scope step by step - bucket reachable, listing allowed, reading allowed - and
   * reports how far it got; a refused step is the result, not an exception.
   */
  S3AccessCheck testAccess(S3Scope scope) throws S3AccessException, InterruptedException;

  /** What this store did so far: requests, throttles, bytes. */
  SourceRequestMeter meter();

  @Override
  void close();
}
