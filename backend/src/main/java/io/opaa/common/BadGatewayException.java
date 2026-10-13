package io.opaa.common;

/**
 * A dependency answered or failed in a way this request cannot recover from (e.g. a transcription
 * endpoint refusing a recording). {@code GlobalExceptionHandler} maps it to {@code 502} with {@link
 * #getMessage()} as the user-facing text and logs nothing: the raiser has already logged the
 * technical cause, so the answer carries no stack trace.
 */
public class BadGatewayException extends RuntimeException {

  public BadGatewayException(String message) {
    super(message);
  }
}
