package io.opaa.common;

/**
 * The caller is authenticated but lacks the role, membership or capability a domain operation
 * requires. {@code GlobalExceptionHandler} maps it to {@code 403} with {@link #getMessage()} as the
 * user-facing text and, when given, {@link #getCode()} as the stable, machine-readable {@code code}
 * of the response (e.g. {@code CAPABILITY_REQUIRED}) - for the cases a client has to act on rather
 * than display. Distinct from {@code org.springframework.security.access.AccessDeniedException},
 * which Spring Security itself raises - either URL-based, from the filter chain, or from a
 * {@code @PreAuthorize}/{@code @Secured} method interceptor - never a domain service throwing it
 * deliberately.
 */
public class AccessDeniedException extends RuntimeException {

  private final String code;

  public AccessDeniedException(String message) {
    this(message, null);
  }

  public AccessDeniedException(String message, String code) {
    super(message);
    this.code = code;
  }

  /** The stable code the response carries, or {@code null} for a refusal with a message only. */
  public String getCode() {
    return code;
  }
}
