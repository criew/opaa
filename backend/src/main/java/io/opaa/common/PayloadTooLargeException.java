package io.opaa.common;

/**
 * A submitted payload (file upload, logo) exceeds a configured size limit. {@code
 * io.opaa.api.GlobalExceptionHandler} maps it to {@code 413} with {@link #getMessage()} as the
 * user-facing text.
 */
public class PayloadTooLargeException extends RuntimeException {

  public PayloadTooLargeException(String message) {
    super(message);
  }
}
