package io.opaa.common;

import java.util.List;
import java.util.Objects;

/**
 * A request failed one or more field-level rules the client has to show at the field, each with a
 * stable code it can map to its own text (e.g. the password policy's {@code TOO_SHORT}). {@code
 * io.opaa.api.GlobalExceptionHandler} maps it to {@code 400} with {@link #getMessage()} as the
 * user-facing text and {@link #fieldErrors()} as {@code fieldErrors}. A {@link ValidationException}
 * without field errors stays what it is - this type is for the cases where the field matters.
 */
public class FieldValidationException extends RuntimeException {

  /** One violated rule of one request field. */
  public record FieldError(String field, String code, String message) {
    public FieldError {
      Objects.requireNonNull(field, "field");
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(message, "message");
    }
  }

  private final List<FieldError> fieldErrors;

  public FieldValidationException(String message, List<FieldError> fieldErrors) {
    super(message);
    if (fieldErrors == null || fieldErrors.isEmpty()) {
      throw new IllegalArgumentException("fieldErrors must name at least one field");
    }
    this.fieldErrors = List.copyOf(fieldErrors);
  }

  public static FieldValidationException of(String field, String code, String message) {
    return new FieldValidationException(message, List.of(new FieldError(field, code, message)));
  }

  public List<FieldError> fieldErrors() {
    return fieldErrors;
  }
}
