package io.opaa.indexing.source;

/**
 * The storage behind an original cannot be reached right now - a temporary condition the caller
 * answers with 503, never with "does not exist". {@link #userMessage()} is the German answer the
 * connector chooses; the exception's own message is for the log only and may name configuration a
 * reader of the document does not see.
 */
public class OriginalUnavailableException extends RuntimeException {

  private final String userMessage;

  public OriginalUnavailableException(String userMessage, String logMessage, Throwable cause) {
    super(logMessage, cause);
    this.userMessage = userMessage;
  }

  /** German, user-facing, free of configuration detail. */
  public String userMessage() {
    return userMessage;
  }
}
