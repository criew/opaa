package io.opaa.common;

/**
 * A request argument is missing, malformed, or otherwise fails a domain validation rule. {@code
 * io.opaa.api.GlobalExceptionHandler} maps it to {@code 400} with {@link #getMessage()} as the
 * user-facing text and, when given, {@link #getCode()} as the stable, machine-readable {@code code}
 * of the response (e.g. {@code TOKEN_INVALID}) - for the cases a client has to act on rather than
 * display.
 */
public class ValidationException extends RuntimeException {

  private final String code;

  public ValidationException(String message) {
    this(message, null);
  }

  public ValidationException(String message, String code) {
    super(message);
    this.code = code;
  }

  /** The stable code the response carries, or {@code null} for a message-only refusal. */
  public String getCode() {
    return code;
  }
}
