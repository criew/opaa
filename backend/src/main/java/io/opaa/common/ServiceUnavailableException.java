package io.opaa.common;

/**
 * A dependency the request needs is temporarily unavailable (e.g. indexing paused, no active chat
 * model configured). {@code GlobalExceptionHandler} maps it to {@code 503} with {@link
 * #getMessage()} as the user-facing text and, when {@link #retryAfterSeconds()} is known, a {@code
 * Retry-After} header. A refusal with a {@link #getCode()} names a known state the client explains
 * (e.g. no model set up yet); like an expected refusal for load it is not logged as a server error.
 */
public class ServiceUnavailableException extends RuntimeException {

  private final long retryAfterSeconds;
  private final String code;

  public ServiceUnavailableException(String message) {
    this(message, 0);
  }

  public ServiceUnavailableException(String message, Throwable cause) {
    super(message, cause);
    this.retryAfterSeconds = 0;
    this.code = null;
  }

  public ServiceUnavailableException(String message, long retryAfterSeconds) {
    super(message);
    this.retryAfterSeconds = retryAfterSeconds;
    this.code = null;
  }

  public ServiceUnavailableException(String message, String code) {
    super(message);
    this.retryAfterSeconds = 0;
    this.code = code;
  }

  /** The stable code the response carries, or {@code null} for a refusal with a message only. */
  public String getCode() {
    return code;
  }

  /** Seconds until a repetition may succeed, or {@code 0} when unknown. */
  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
