package io.opaa.common;

/**
 * The caller is authenticated but lacks the role or membership a domain operation requires. {@code
 * io.opaa.api.GlobalExceptionHandler} maps it to {@code 403} with {@link #getMessage()} as the
 * user-facing text. Distinct from {@code
 * org.springframework.security.access.AccessDeniedException}, which Spring Security itself raises -
 * either URL-based, from the filter chain, or from a {@code @PreAuthorize}/{@code @Secured} method
 * interceptor - never a domain service throwing it deliberately.
 */
public class AccessDeniedException extends RuntimeException {

  public AccessDeniedException(String message) {
    super(message);
  }
}
