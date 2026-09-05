package io.opaa.indexing.source;

/**
 * A run's own, German, user-facing reason to end {@code FAILED} - thrown from a run body so the
 * {@link IndexingRunTemplate} records the message as the job's error and logs it as a warning
 * rather than as an unexpected failure.
 */
public class IndexingRunFailedException extends RuntimeException {

  public IndexingRunFailedException(String message) {
    super(message);
  }

  public IndexingRunFailedException(String message, Throwable cause) {
    super(message, cause);
  }
}
