package io.opaa.indexing.source.s3;

/**
 * What {@link S3ObjectStore#testAccess} found out about one scope, step by step. A refused step
 * ends the probe; {@code failure} carries its diagnosis for the connection test to render.
 *
 * @param readAllowed {@code null} when the scope holds no object to try reading
 * @param objectCount the objects the first listing page showed (folder markers included)
 * @param objectCountIsLowerBound {@code true} when that page was not the last one
 * @param failure the exception of the refused step, {@code null} when every step passed
 */
public record S3AccessCheck(
    boolean bucketReachable,
    boolean listAllowed,
    Boolean readAllowed,
    int objectCount,
    boolean objectCountIsLowerBound,
    S3AccessException failure) {

  public boolean passed() {
    return failure == null;
  }
}
