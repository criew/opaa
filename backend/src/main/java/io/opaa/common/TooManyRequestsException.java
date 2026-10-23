package io.opaa.common;

/**
 * The request was refused because a concurrency or rate ceiling was reached - it may succeed when
 * repeated. {@code GlobalExceptionHandler} maps it to {@code 429} with {@link #getMessage()} as the
 * user-facing text and, when {@link #retryAfterSeconds()} is known, a {@code Retry-After} header -
 * the same answer {@code RateLimitFilter} gives for an overloaded endpoint.
 */
public class TooManyRequestsException extends RuntimeException {

  /** The one message of every rate-limit refusal, in the filter and here. */
  public static final String MESSAGE =
      "Zu viele Anfragen — bitte versuchen Sie es in Kürze erneut.";

  private final long retryAfterSeconds;
  private final String code;

  public TooManyRequestsException(String message) {
    this(message, 0);
  }

  public TooManyRequestsException(String message, Throwable cause) {
    super(message, cause);
    this.retryAfterSeconds = 0;
    this.code = null;
  }

  /** A rate-limit refusal with {@link #MESSAGE} and the seconds a client should wait. */
  public TooManyRequestsException(long retryAfterSeconds) {
    this(MESSAGE, retryAfterSeconds);
  }

  public TooManyRequestsException(String message, long retryAfterSeconds) {
    this(message, retryAfterSeconds, null);
  }

  /** A refusal by an operation's own limit with the stable {@code code} a client acts on. */
  public TooManyRequestsException(String message, long retryAfterSeconds, String code) {
    super(message);
    this.retryAfterSeconds = retryAfterSeconds;
    this.code = code;
  }

  /** The stable code the response carries, or {@code null}. */
  public String getCode() {
    return code;
  }

  /** Seconds until a repetition may succeed, or {@code 0} when unknown. */
  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
