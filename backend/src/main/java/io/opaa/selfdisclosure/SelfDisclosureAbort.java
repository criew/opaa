package io.opaa.selfdisclosure;

/**
 * Ends a running export from inside the stream: the deadline passed, the instance shuts down or the
 * reservation was lost. Thrown through the exporter, which does not catch it.
 */
final class SelfDisclosureAbort extends RuntimeException {

  private final AbortCause cause;

  SelfDisclosureAbort(AbortCause cause, String message) {
    super(message);
    this.cause = cause;
  }

  AbortCause abortCause() {
    return cause;
  }

  /** Why an export ended without being delivered; the {@code abortCause} of the audit event. */
  enum AbortCause {
    CLIENT_DISCONNECTED,
    TIMEOUT,
    SHUTDOWN,
    ERROR
  }
}
