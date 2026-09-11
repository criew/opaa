package io.opaa.common;

/**
 * The requested change conflicts with the current state of the resource (e.g. a uniqueness rule, a
 * lifecycle guard). {@code io.opaa.api.GlobalExceptionHandler} maps it to {@code 409} with {@link
 * #getMessage()} as the user-facing text and, when given, {@link #getCode()} as the stable,
 * machine-readable {@code code} of the response (e.g. {@code LAST_LOGIN_CAPABLE_ADMIN}) - for the
 * cases a client has to act on rather than display.
 */
public class ConflictException extends RuntimeException {

  private final String code;

  public ConflictException(String message) {
    this(message, (String) null);
  }

  public ConflictException(String message, Throwable cause) {
    super(message, cause);
    this.code = null;
  }

  public ConflictException(String message, String code) {
    super(message);
    this.code = code;
  }

  /** The stable code the response carries, or {@code null} for a conflict with a message only. */
  public String getCode() {
    return code;
  }
}
