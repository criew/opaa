package io.opaa.common;

/**
 * A requested resource does not exist, or is not visible to the caller (organization/space boundary
 * treated as absence rather than {@link AccessDeniedException}, see the individual throw sites).
 * {@code GlobalExceptionHandler} maps it to {@code 404} with {@link #getMessage()} as the
 * user-facing text and, when given, {@link #getCode()} as the stable, machine-readable {@code code}
 * of the response - only where the caller may know the resource exists.
 */
public class NotFoundException extends RuntimeException {

  private final String code;

  public NotFoundException(String message) {
    this(message, null);
  }

  public NotFoundException(String message, String code) {
    super(message);
    this.code = code;
  }

  /** The stable code the response carries, or {@code null} for a 404 with a message only. */
  public String getCode() {
    return code;
  }
}
