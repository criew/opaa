package io.opaa.common;

/**
 * A request argument is missing, malformed, or otherwise fails a domain validation rule. {@code
 * io.opaa.api.GlobalExceptionHandler} maps it to {@code 400} with {@link #getMessage()} as the
 * user-facing text.
 */
public class ValidationException extends RuntimeException {

  public ValidationException(String message) {
    super(message);
  }
}
