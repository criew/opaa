package io.opaa.common;

/**
 * A dependency the request needs is temporarily unavailable (e.g. indexing paused, no active chat
 * model configured). {@code GlobalExceptionHandler} maps it to {@code 503} with {@link
 * #getMessage()} as the user-facing text and, when {@link #retryAfterSeconds()} is known, a {@code
 * Retry-After} header; such an expected refusal for load is not logged as a server error.
 */
public class ServiceUnavailableException extends RuntimeException {

  private final long retryAfterSeconds;

  public ServiceUnavailableException(String message) {
    this(message, 0);
  }

  public ServiceUnavailableException(String message, Throwable cause) {
    super(message, cause);
    this.retryAfterSeconds = 0;
  }

  public ServiceUnavailableException(String message, long retryAfterSeconds) {
    super(message);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /** Seconds until a repetition may succeed, or {@code 0} when unknown. */
  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
