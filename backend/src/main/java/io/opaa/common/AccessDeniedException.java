package io.opaa.common;

/**
 * The caller is authenticated but lacks the role or membership a domain operation requires. {@code
 * io.opaa.api.GlobalExceptionHandler} maps it to {@code 403} with {@link #getMessage()} as the
 * user-facing text. Distinct from {@code
 * org.springframework.security.access.AccessDeniedException}, which the security filter chain
 * itself raises before a request reaches a service.
 */
public class AccessDeniedException extends RuntimeException {

  public AccessDeniedException(String message) {
    super(message);
  }
}
