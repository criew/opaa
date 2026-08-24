package io.opaa.common;

/**
 * The requested change conflicts with the current state of the resource (e.g. a uniqueness rule, a
 * lifecycle guard). {@code io.opaa.api.GlobalExceptionHandler} maps it to {@code 409} with {@link
 * #getMessage()} as the user-facing text.
 */
public class ConflictException extends RuntimeException {

  public ConflictException(String message) {
    super(message);
  }

  public ConflictException(String message, Throwable cause) {
    super(message, cause);
  }
}
