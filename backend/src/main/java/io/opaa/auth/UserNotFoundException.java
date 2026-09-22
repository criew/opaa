package io.opaa.auth;

import io.opaa.common.NotFoundException;

/**
 * A user row is absent, or invisible across the organization boundary (see {@code
 * UserService#updateRole}). A {@link NotFoundException} so that {@code
 * io.opaa.api.GlobalExceptionHandler} answers it through the shared funnel with the usual envelope
 * (#1799).
 */
public class UserNotFoundException extends NotFoundException {
  public UserNotFoundException(String message) {
    super(message);
  }
}
