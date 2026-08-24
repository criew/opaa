package io.opaa.common;

/**
 * The caller's authenticated identity no longer resolves to a known user (e.g. a JWT subject whose
 * {@code User} row was removed after the token was issued). {@code
 * io.opaa.api.GlobalExceptionHandler} maps it to {@code 401} with {@link #getMessage()} as the
 * user-facing text.
 */
public class UnauthorizedException extends RuntimeException {

  public UnauthorizedException(String message) {
    super(message);
  }
}
