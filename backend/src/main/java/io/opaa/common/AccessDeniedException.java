package io.opaa.common;

/**
 * The caller is authenticated but lacks the role, membership or capability a domain operation
 * requires. {@code GlobalExceptionHandler} maps it to {@code 403} with {@link #getMessage()} as the
 * user-facing text and, when given, {@link #getCode()} as the stable, machine-readable {@code code}
 * of the response (e.g. {@code CAPABILITY_REQUIRED}) - for the cases a client has to act on rather
 * than display, and, when given, {@link #getReason()} as its {@code reason} qualifier. Distinct
 * from {@code org.springframework.security.access.AccessDeniedException}, which Spring Security
 * itself raises - either URL-based, from the filter chain, or from a
 * {@code @PreAuthorize}/{@code @Secured} method interceptor - never a domain service throwing it
 * deliberately.
 */
public class AccessDeniedException extends RuntimeException {

  private final String code;
  private final String reason;

  public AccessDeniedException(String message) {
    this(message, null);
  }

  public AccessDeniedException(String message, String code) {
    this(message, code, null);
  }

  public AccessDeniedException(String message, String code, String reason) {
    super(message);
    this.code = code;
    this.reason = reason;
  }

  /** The stable code the response carries, or {@code null} for a refusal with a message only. */
  public String getCode() {
    return code;
  }

  /**
   * The qualifier of {@link #getCode()} the response carries as {@code reason}, or {@code null}.
   */
  public String getReason() {
    return reason;
  }
}
