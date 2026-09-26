package io.opaa.auth;

import io.opaa.common.NotFoundException;

/**
 * A user row is absent, or invisible across the organization boundary (see {@code
 * UserService#updateRole}). Thrown in the dispatcher, it answers as any other {@link
 * NotFoundException}: {@code GlobalExceptionHandler} maps it to {@code 404} with the usual
 * envelope.
 */
public class UserNotFoundException extends NotFoundException {
  public UserNotFoundException(String message) {
    super(message);
  }
}
