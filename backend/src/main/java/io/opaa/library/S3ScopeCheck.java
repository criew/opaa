package io.opaa.library;

/**
 * The connection test's finding for one scope of an S3 library (ADR-0027, #1376): how far the
 * three-step probe got - bucket reachable, listing allowed, reading allowed - and what the first
 * listing page counted. {@code message} carries the German diagnosis of the refused step, {@code
 * null} when every step passed.
 *
 * @param readAllowed {@code null} when the scope holds no object to try reading
 * @param objectCountIsLowerBound {@code true} when the first page was not the last one
 */
public record S3ScopeCheck(
    String bucket,
    String prefix,
    boolean bucketReachable,
    boolean listAllowed,
    Boolean readAllowed,
    long objectCount,
    boolean objectCountIsLowerBound,
    String message) {

  /** Whether every step this scope allowed to probe passed. */
  public boolean passed() {
    return message == null;
  }
}
