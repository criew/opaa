package io.opaa.query.sandbox;

/** The sandbox server failed or could not be reached; fails the turn like any tool error. */
public final class SandboxException extends RuntimeException {

  public SandboxException(String message) {
    super(message);
  }

  public SandboxException(String message, Throwable cause) {
    super(message, cause);
  }
}
