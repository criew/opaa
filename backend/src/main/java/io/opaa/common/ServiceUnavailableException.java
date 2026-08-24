package io.opaa.common;

/**
 * A dependency the request needs is temporarily unavailable (e.g. indexing paused, no active chat
 * model configured). {@code io.opaa.api.GlobalExceptionHandler} maps it to {@code 503} with {@link
 * #getMessage()} as the user-facing text.
 */
public class ServiceUnavailableException extends RuntimeException {

  public ServiceUnavailableException(String message) {
    super(message);
  }

  public ServiceUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
