package io.opaa.common;

/**
 * A requested resource does not exist, or is not visible to the caller (organization/space boundary
 * treated as absence rather than {@link AccessDeniedException}, see the individual throw sites).
 * {@code io.opaa.api.GlobalExceptionHandler} maps it to {@code 404} with {@link #getMessage()} as
 * the user-facing text.
 */
public class NotFoundException extends RuntimeException {

  public NotFoundException(String message) {
    super(message);
  }
}
