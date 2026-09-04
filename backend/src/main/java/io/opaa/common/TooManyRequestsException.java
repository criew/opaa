package io.opaa.common;

/**
 * The request was refused because a concurrency or rate ceiling was reached - it may succeed when
 * repeated. {@code io.opaa.api.GlobalExceptionHandler} maps it to {@code 429} with {@link
 * #getMessage()} as the user-facing text, the same status {@code RateLimitFilter} already answers
 * with for an overloaded endpoint.
 */
public class TooManyRequestsException extends RuntimeException {

  public TooManyRequestsException(String message) {
    super(message);
  }

  public TooManyRequestsException(String message, Throwable cause) {
    super(message, cause);
  }
}
